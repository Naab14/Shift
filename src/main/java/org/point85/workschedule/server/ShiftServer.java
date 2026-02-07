package org.point85.workschedule.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;

import org.point85.workschedule.*;

/**
 * Standalone HTTP server for the Work Schedule app.
 * Uses JDK built-in HttpServer -- zero external dependencies.
 *
 * Serves:
 *   - Static files at /  (index.html, style.css, app.js)
 *   - REST API at /api/schedules/*
 */
public class ShiftServer {
    private static final int PORT = 8080;
    private static final Map<String, WorkSchedule> schedules = new ConcurrentHashMap<>();
    private static String staticDir;

    public static void main(String[] args) throws Exception {
        // Find static files directory
        staticDir = findStaticDir();
        System.out.println("Static files: " + staticDir);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // API routes
        server.createContext("/api/schedules", ShiftServer::handleApi);

        // Static files (must be last - catches everything)
        server.createContext("/", ShiftServer::handleStatic);

        server.start();
        System.out.println("===========================================");
        System.out.println("  Arbetsschema is running!");
        System.out.println("  Open: http://localhost:" + PORT);
        System.out.println("  API:  http://localhost:" + PORT + "/api/schedules");
        System.out.println("===========================================");
    }

    // ===== Static File Handler =====

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        File file = new File(staticDir, path);
        if (!file.exists() || !file.isFile()) {
            // Serve index.html for SPA routes
            file = new File(staticDir, "index.html");
        }

        if (!file.exists()) {
            sendJson(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }

        String contentType = guessContentType(path);
        byte[] bytes = Files.readAllBytes(file.toPath());
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ===== API Router =====

    private static void handleApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();

        // Set locale from Accept-Language header
        String lang = ex.getRequestHeaders().getFirst("Accept-Language");
        if (lang != null && lang.startsWith("sv")) {
            WorkSchedule.setLocale(new Locale("sv", "SE"));
        }
        // Override with ?lang= param
        String langParam = getParam(query, "lang");
        if (langParam != null) {
            WorkSchedule.setLocale(Locale.forLanguageTag(langParam));
        }

        try {
            // Remove /api/schedules prefix
            String subPath = path.substring("/api/schedules".length());

            if (subPath.isEmpty() || subPath.equals("/")) {
                if ("POST".equals(method)) {
                    createSchedule(ex);
                } else {
                    listSchedules(ex);
                }
                return;
            }

            // Parse /{name}/...
            String[] parts = subPath.substring(1).split("/", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name());

            if (parts.length == 1) {
                if ("GET".equals(method)) getSchedule(ex, name);
                else if ("DELETE".equals(method)) deleteSchedule(ex, name);
                else sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String rest = parts[1];

            // GET /{name}/shifts/{date}
            if (rest.startsWith("shifts/at")) {
                getShiftInstancesForTime(ex, name, query);
            } else if (rest.startsWith("shifts/")) {
                String dateStr = rest.substring("shifts/".length());
                getShiftInstances(ex, name, dateStr);
            }
            // GET /{name}/working-time?from=&to=
            else if (rest.equals("working-time")) {
                getWorkingTime(ex, name, query);
            }
            // POST /{name}/holidays/swedish/{year}
            else if (rest.startsWith("holidays/swedish/")) {
                String yearStr = rest.substring("holidays/swedish/".length());
                addSwedishHolidays(ex, name, Integer.parseInt(yearStr));
            }
            else {
                sendJson(ex, 404, "{\"error\":\"Not found\"}");
            }

        } catch (Exception e) {
            sendJson(ex, 400, "{\"status\":400,\"message\":" + jsonString(e.getMessage()) + "}");
        } finally {
            WorkSchedule.clearLocale();
        }
    }

    // ===== API Handlers =====

    private static void createSchedule(HttpExchange ex) throws Exception {
        String body = readBody(ex);
        Map<String, Object> json = parseJson(body);

        String name = (String) json.get("name");
        String desc = (String) json.getOrDefault("description", name);

        if (schedules.containsKey(name)) {
            sendJson(ex, 409, "{\"status\":409,\"message\":" + jsonString("Schedule already exists: " + name) + "}");
            return;
        }

        WorkSchedule ws = new WorkSchedule(name, desc);

        // Create shifts
        List<Map<String, Object>> shifts = (List<Map<String, Object>>) json.getOrDefault("shifts", Collections.emptyList());
        for (Map<String, Object> s : shifts) {
            Shift shift = ws.createShift(
                (String) s.get("name"),
                (String) s.getOrDefault("description", s.get("name")),
                LocalTime.parse((String) s.get("start")),
                Duration.parse((String) s.get("duration"))
            );
            List<Map<String, Object>> breaks = (List<Map<String, Object>>) s.getOrDefault("breaks", Collections.emptyList());
            for (Map<String, Object> b : breaks) {
                shift.createBreak(
                    (String) b.get("name"),
                    (String) b.getOrDefault("description", b.get("name")),
                    LocalTime.parse((String) b.get("start")),
                    Duration.parse((String) b.get("duration"))
                );
            }
        }

        // Create rotations
        List<Map<String, Object>> rotations = (List<Map<String, Object>>) json.getOrDefault("rotations", Collections.emptyList());
        for (Map<String, Object> r : rotations) {
            Rotation rot = ws.createRotation(
                (String) r.get("name"),
                (String) r.getOrDefault("description", r.get("name"))
            );
            List<Map<String, Object>> segments = (List<Map<String, Object>>) r.getOrDefault("segments", Collections.emptyList());
            for (Map<String, Object> seg : segments) {
                Shift shift = findShift(ws, (String) seg.get("shiftName"));
                rot.addSegment(shift, toInt(seg.get("daysOn")), toInt(seg.get("daysOff")));
            }
        }

        // Create teams
        List<Map<String, Object>> teams = (List<Map<String, Object>>) json.getOrDefault("teams", Collections.emptyList());
        for (Map<String, Object> t : teams) {
            Rotation rot = findRotation(ws, (String) t.get("rotationName"));
            Team team = ws.createTeam(
                (String) t.get("name"),
                (String) t.getOrDefault("description", t.get("name")),
                rot,
                LocalDate.parse((String) t.get("rotationStart"))
            );
            List<Map<String, Object>> members = (List<Map<String, Object>>) t.getOrDefault("members", Collections.emptyList());
            for (Map<String, Object> m : members) {
                team.addMember(new TeamMember(
                    (String) m.get("name"),
                    (String) m.getOrDefault("description", m.get("name")),
                    (String) m.getOrDefault("memberID", "")
                ));
            }
        }

        // Create non-working periods
        List<Map<String, Object>> nwps = (List<Map<String, Object>>) json.getOrDefault("nonWorkingPeriods", Collections.emptyList());
        for (Map<String, Object> n : nwps) {
            ws.createNonWorkingPeriod(
                (String) n.get("name"),
                (String) n.getOrDefault("description", n.get("name")),
                LocalDateTime.parse((String) n.get("startDateTime")),
                Duration.parse((String) n.get("duration"))
            );
        }

        schedules.put(name, ws);
        sendJson(ex, 201, scheduleToJson(ws));
    }

    private static void listSchedules(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WorkSchedule ws : schedules.values()) {
            if (!first) sb.append(",");
            sb.append(scheduleToJson(ws));
            first = false;
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    private static void getSchedule(HttpExchange ex, String name) throws IOException {
        WorkSchedule ws = schedules.get(name);
        if (ws == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        sendJson(ex, 200, scheduleToJson(ws));
    }

    private static void deleteSchedule(HttpExchange ex, String name) throws IOException {
        if (schedules.remove(name) == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        ex.sendResponseHeaders(204, -1);
        ex.getResponseBody().close();
    }

    private static void getShiftInstances(HttpExchange ex, String name, String dateStr) throws Exception {
        WorkSchedule ws = schedules.get(name);
        if (ws == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        LocalDate date = LocalDate.parse(dateStr);
        List<ShiftInstance> instances = ws.getShiftInstancesForDay(date);
        sendJson(ex, 200, shiftInstancesToJson(instances));
    }

    private static void getShiftInstancesForTime(HttpExchange ex, String name, String query) throws Exception {
        WorkSchedule ws = schedules.get(name);
        if (ws == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        String dtStr = getParam(query, "dateTime");
        LocalDateTime dt = LocalDateTime.parse(dtStr);
        List<ShiftInstance> instances = ws.getShiftInstancesForTime(dt);
        sendJson(ex, 200, shiftInstancesToJson(instances));
    }

    private static void getWorkingTime(HttpExchange ex, String name, String query) throws Exception {
        WorkSchedule ws = schedules.get(name);
        if (ws == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        LocalDateTime from = LocalDateTime.parse(getParam(query, "from"));
        LocalDateTime to = LocalDateTime.parse(getParam(query, "to"));
        Duration working = ws.calculateWorkingTime(from, to);
        Duration nonWorking = ws.calculateNonWorkingTime(from, to);

        String json = "{" +
            "\"from\":" + jsonString(from.toString()) + "," +
            "\"to\":" + jsonString(to.toString()) + "," +
            "\"workingTime\":" + jsonString(working.toString()) + "," +
            "\"nonWorkingTime\":" + jsonString(nonWorking.toString()) +
            "}";
        sendJson(ex, 200, json);
    }

    private static void addSwedishHolidays(HttpExchange ex, String name, int year) throws Exception {
        WorkSchedule ws = schedules.get(name);
        if (ws == null) {
            sendJson(ex, 404, "{\"status\":404,\"message\":" + jsonString("Schedule not found: " + name) + "}");
            return;
        }
        addSwedishHolidaysToSchedule(ws, year);
        sendJson(ex, 200, scheduleToJson(ws));
    }

    // ===== Swedish Holidays =====

    private static void addSwedishHolidaysToSchedule(WorkSchedule ws, int year) throws Exception {
        Duration day = Duration.ofHours(24);

        // Fixed holidays
        ws.createNonWorkingPeriod("Ny\u00e5rsdagen", "New Year's Day",
            LocalDateTime.of(year, 1, 1, 0, 0), day);
        ws.createNonWorkingPeriod("Trettondedag jul", "Epiphany",
            LocalDateTime.of(year, 1, 6, 0, 0), day);
        ws.createNonWorkingPeriod("F\u00f6rsta maj", "May Day",
            LocalDateTime.of(year, 5, 1, 0, 0), day);
        ws.createNonWorkingPeriod("Sveriges nationaldag", "National Day",
            LocalDateTime.of(year, 6, 6, 0, 0), day);
        ws.createNonWorkingPeriod("Julafton", "Christmas Eve",
            LocalDateTime.of(year, 12, 24, 0, 0), day);
        ws.createNonWorkingPeriod("Juldagen", "Christmas Day",
            LocalDateTime.of(year, 12, 25, 0, 0), day);
        ws.createNonWorkingPeriod("Annandag jul", "Boxing Day",
            LocalDateTime.of(year, 12, 26, 0, 0), day);
        ws.createNonWorkingPeriod("Ny\u00e5rsafton", "New Year's Eve",
            LocalDateTime.of(year, 12, 31, 0, 0), day);

        // Easter-based
        LocalDate easter = calculateEaster(year);
        ws.createNonWorkingPeriod("L\u00e5ngfredagen", "Good Friday",
            LocalDateTime.of(easter.minusDays(2), LocalTime.MIDNIGHT), day);
        ws.createNonWorkingPeriod("P\u00e5skafton", "Easter Saturday",
            LocalDateTime.of(easter.minusDays(1), LocalTime.MIDNIGHT), day);
        ws.createNonWorkingPeriod("P\u00e5skdagen", "Easter Sunday",
            LocalDateTime.of(easter, LocalTime.MIDNIGHT), day);
        ws.createNonWorkingPeriod("Annandag p\u00e5sk", "Easter Monday",
            LocalDateTime.of(easter.plusDays(1), LocalTime.MIDNIGHT), day);
        ws.createNonWorkingPeriod("Kristi himmelsf\u00e4rdsdag", "Ascension Day",
            LocalDateTime.of(easter.plusDays(39), LocalTime.MIDNIGHT), day);

        // Midsummer Eve: Friday between June 19-25
        LocalDate midsummer = LocalDate.of(year, 6, 19)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        ws.createNonWorkingPeriod("Midsommarafton", "Midsummer Eve",
            LocalDateTime.of(midsummer, LocalTime.MIDNIGHT), day);
        ws.createNonWorkingPeriod("Midsommardagen", "Midsummer Day",
            LocalDateTime.of(midsummer.plusDays(1), LocalTime.MIDNIGHT), day);

        // All Saints' Day: Saturday between Oct 31 - Nov 6
        LocalDate allSaints = LocalDate.of(year, 10, 31)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        ws.createNonWorkingPeriod("Alla helgons dag", "All Saints' Day",
            LocalDateTime.of(allSaints, LocalTime.MIDNIGHT), day);
    }

    private static LocalDate calculateEaster(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int dayOfMonth = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, dayOfMonth);
    }

    // ===== JSON Serialization =====

    private static String scheduleToJson(WorkSchedule ws) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":").append(jsonString(ws.getName()));
        sb.append(",\"description\":").append(jsonString(ws.getDescription()));

        // Shifts
        sb.append(",\"shifts\":[");
        boolean first = true;
        for (Shift s : ws.getShifts()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":").append(jsonString(s.getName()));
            sb.append(",\"description\":").append(jsonString(s.getDescription()));
            sb.append(",\"start\":").append(jsonString(s.getStart().toString()));
            sb.append(",\"duration\":").append(jsonString(s.getDuration().toString()));
            sb.append(",\"breaks\":[");
            boolean bf = true;
            for (Break b : s.getBreaks()) {
                if (!bf) sb.append(",");
                sb.append("{\"name\":").append(jsonString(b.getName()));
                sb.append(",\"start\":").append(jsonString(b.getStart().toString()));
                sb.append(",\"duration\":").append(jsonString(b.getDuration().toString())).append("}");
                bf = false;
            }
            sb.append("]}");
            first = false;
        }
        sb.append("]");

        // Rotations
        sb.append(",\"rotations\":[");
        first = true;
        for (Rotation r : ws.getRotations()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":").append(jsonString(r.getName()));
            sb.append(",\"description\":").append(jsonString(r.getDescription()));
            sb.append(",\"segments\":[");
            boolean sf = true;
            for (RotationSegment seg : r.getRotationSegments()) {
                if (!sf) sb.append(",");
                sb.append("{\"shiftName\":").append(jsonString(seg.getStartingShift().getName()));
                sb.append(",\"daysOn\":").append(seg.getDaysOn());
                sb.append(",\"daysOff\":").append(seg.getDaysOff()).append("}");
                sf = false;
            }
            sb.append("]}");
            first = false;
        }
        sb.append("]");

        // Teams
        sb.append(",\"teams\":[");
        first = true;
        for (Team t : ws.getTeams()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":").append(jsonString(t.getName()));
            sb.append(",\"description\":").append(jsonString(t.getDescription()));
            sb.append(",\"rotationName\":").append(jsonString(t.getRotation().getName()));
            sb.append(",\"rotationStart\":").append(jsonString(t.getRotationStart().toString()));
            sb.append(",\"members\":[");
            boolean mf = true;
            for (TeamMember m : t.getAssignedMembers()) {
                if (!mf) sb.append(",");
                sb.append("{\"name\":").append(jsonString(m.getName()));
                sb.append(",\"memberID\":").append(jsonString(m.getMemberID() != null ? m.getMemberID() : "")).append("}");
                mf = false;
            }
            sb.append("]}");
            first = false;
        }
        sb.append("]");

        // Non-working periods
        sb.append(",\"nonWorkingPeriods\":[");
        first = true;
        for (NonWorkingPeriod p : ws.getNonWorkingPeriods()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":").append(jsonString(p.getName()));
            sb.append(",\"description\":").append(jsonString(p.getDescription()));
            sb.append(",\"startDateTime\":").append(jsonString(p.getStartDateTime().toString()));
            sb.append(",\"duration\":").append(jsonString(p.getDuration().toString())).append("}");
            first = false;
        }
        sb.append("]}");

        return sb.toString();
    }

    private static String shiftInstancesToJson(List<ShiftInstance> instances) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ShiftInstance si : instances) {
            if (!first) sb.append(",");
            sb.append("{\"teamName\":").append(jsonString(si.getTeam().getName()));
            sb.append(",\"shiftName\":").append(jsonString(si.getShift().getName()));
            sb.append(",\"startTime\":").append(jsonString(si.getStartTime().toString()));
            sb.append(",\"endTime\":").append(jsonString(si.getEndTime().toString())).append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // ===== Helpers =====

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String getParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv[0].equals(name) && kv.length > 1) {
                try { return URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()); }
                catch (Exception e) { return kv[1]; }
            }
        }
        return null;
    }

    private static Shift findShift(WorkSchedule ws, String name) throws Exception {
        for (Shift s : ws.getShifts()) if (s.getName().equals(name)) return s;
        throw new Exception("Shift not found: " + name);
    }

    private static Rotation findRotation(WorkSchedule ws, String name) throws Exception {
        for (Rotation r : ws.getRotations()) if (r.getName().equals(name)) return r;
        throw new Exception("Rotation not found: " + name);
    }

    private static int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(val.toString().replaceAll("\\.0$", ""));
    }

    private static String findStaticDir() {
        // Try various locations
        String[] candidates = {
            "src/main/resources/static",
            "../src/main/resources/static",
            "static",
            "."
        };
        for (String c : candidates) {
            File f = new File(c, "index.html");
            if (f.exists()) return new File(c).getAbsolutePath();
        }
        return "src/main/resources/static";
    }

    // ===== Minimal JSON Parser =====
    // Handles the JSON structure our frontend sends (objects, arrays, strings, numbers)

    private static Map<String, Object> parseJson(String json) {
        return new JsonParser(json.trim()).parseObject();
    }

    private static class JsonParser {
        private final String s;
        private int pos = 0;

        JsonParser(String s) { this.s = s; }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() { skipWhitespace(); return pos < s.length() ? s.charAt(pos) : 0; }
        private char next() { skipWhitespace(); return s.charAt(pos++); }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            next(); // {
            if (peek() == '}') { pos++; return map; }
            while (true) {
                String key = parseString();
                next(); // :
                Object val = parseValue();
                map.put(key, val);
                if (peek() == '}') { pos++; return map; }
                next(); // ,
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            next(); // [
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                if (peek() == ']') { pos++; return list; }
                next(); // ,
            }
        }

        Object parseValue() {
            char c = peek();
            if (c == '"') return parseString();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == 't') { pos += 4; return Boolean.TRUE; }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }

        String parseString() {
            next(); // opening "
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            skipWhitespace();
            int start = pos;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') isDouble = true;
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || Character.isDigit(c)) pos++;
                else break;
            }
            String num = s.substring(start, pos);
            return isDouble ? Double.parseDouble(num) : Long.parseLong(num);
        }
    }
}

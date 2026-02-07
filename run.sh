#!/bin/bash
# Build and run Arbetsschema without Maven - uses only javac + JDK built-in HTTP server
set -e

echo "=== Building Arbetsschema ==="

# Clean
rm -rf build/
mkdir -p build/classes

# Copy resources
cp -r src/main/resources/* build/classes/

# Compile all Java source files
echo "Compiling..."
find src/main/java -name "*.java" > build/sources.txt
javac --release 11 -d build/classes @build/sources.txt

echo "Build complete!"
echo ""
echo "=== Starting server ==="
cd build/classes
java org.point85.workschedule.server.ShiftServer

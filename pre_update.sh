#!/bin/bash
set -euo pipefail

echo "=== Applying all patches ==="
./gradlew applyAllPatches --quiet

echo "=== Enabling Git file patches ==="
BUILD_FILES=(
  "build.gradle.kts"
  "petiole-server/build.gradle.kts"
)

for file in "${BUILD_FILES[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "ERROR: $file not found"
    exit 1
  fi

  sed -i 's/gitFilePatches *= *false/gitFilePatches = true/' "$file"
done

echo "=== Rebuilding single-file patches ==="
./gradlew rebuildFoliaSingleFilePatches --quiet

echo "=== Rebuilding file patches as Git patches ==="
./gradlew rebuildAllServerFilePatches --quiet
./gradlew rebuildPaperApiFilePatches --quiet

echo "=== Moving file patches to _unapplied ==="
dirs=(
  "petiole-server/minecraft-patches/sources petiole-server/minecraft-patches/sources_unapplied"
  "petiole-server/paper-patches/files petiole-server/paper-patches/files_unapplied"
  "petiole-server/folia-patches/files petiole-server/folia-patches/files_unapplied"
  "petiole-api/paper-patches/files petiole-api/paper-patches/files_unapplied"
  "petiole-api/folia-patches/files petiole-api/folia-patches/files_unapplied"
)

for dir in "${dirs[@]}"; do
  set -- $dir
  src=$1
  dest=$2

  if [[ -d "$src" ]]; then
    mkdir -p "$dest"
    mv "$src"/* "$dest"/ 2>/dev/null || true
  fi
done

echo "=== REMINDER: ==="
echo "After moving patches back to their directories during update, run the following tasks to apply them and move failed ones:"
echo "  ./gradlew applyOrMovePaperServerFilePatches"
echo "  ./gradlew applyOrMovePaperApiFilePatches"

#!/usr/bin/env bash

set -ex  # Exit on any error

SCRIPT_DIR="$(dirname "$0")"
SCRIPT_DIR="$(realpath "$SCRIPT_DIR")"
BASEDIR="$(dirname "$SCRIPT_DIR")"

# Create main and test js directories
mkdir -pv "$BASEDIR"/resources/public/js/
mkdir -pv "$BASEDIR"/resources/public/js/test/js/
mkdir -pv "$BASEDIR"/resources/public/js/test/extensions/lang/rholang/tree-sitter/queries/

# Copy Tree-Sitter WASM to main and test directories
cp -v "$BASEDIR"/node_modules/web-tree-sitter/tree-sitter.wasm "$BASEDIR"/resources/public/js/
cp -v "$BASEDIR"/node_modules/web-tree-sitter/tree-sitter.wasm "$BASEDIR"/resources/public/js/test/js/

# Copy Rholang Tree-Sitter WASM to extension directory
cp -v "$BASEDIR"/node_modules/@f1r3fly-io/tree-sitter-rholang-js/tree-sitter-rholang.wasm "$BASEDIR"/resources/public/extensions/lang/rholang/tree-sitter/

# Copy entire extensions directory to test/js for test server access
cp -rv "$BASEDIR"/resources/public/extensions/ "$BASEDIR"/resources/public/js/test/

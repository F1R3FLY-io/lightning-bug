#!/bin/sh
# Creates necessary directories and copies WASM files for Tree-Sitter and Rholang grammar
# to the main and test public directories to ensure accessibility during development and testing.

set -ex  # Exit on any error

# Create main and test js directories
mkdir -p resources/public/js
mkdir -p resources/public/js/test/extensions/lang/rholang/tree-sitter/queries

# Copy Tree-Sitter WASM to main and test directories
cp node_modules/web-tree-sitter/tree-sitter.wasm resources/public/js/tree-sitter.wasm
cp node_modules/web-tree-sitter/tree-sitter.wasm resources/public/js/test/

# Copy entire extensions directory to test/js for test server access
cp -r resources/public/extensions resources/public/js/test/

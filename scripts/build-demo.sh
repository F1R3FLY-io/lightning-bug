#!/usr/bin/env bash

set -ex

SCRIPT_DIR="$(dirname "$0")"
SCRIPT_DIR="$(realpath "$SCRIPT_DIR")"
BASEDIR="$(dirname "$SCRIPT_DIR")"
DEMO_DIR="$BASEDIR"/resources/public/demo

pushd "$BASEDIR"
npm install
npx shadow-cljs release libs
popd

pushd "$DEMO_DIR"
npm install
mkdir -pv "$DEMO_DIR"/js
cp -v "$DEMO_DIR"/node_modules/web-tree-sitter/tree-sitter.wasm "$DEMO_DIR"/js/
cp -rv "$DEMO_DIR"/node_modules/lightning-bug/resources/public/extensions/ "$DEMO_DIR"/
popd

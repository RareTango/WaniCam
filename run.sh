#!/usr/bin/env sh
# Start WaniCam (macOS / Linux). Extra options are passed through, e.g. ./run.sh --port 9000
cd "$(dirname "$0")" && exec python3 -m wanicam "$@"

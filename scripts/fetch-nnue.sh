#!/bin/sh
# Downloads the Stockfish 18 NNUE networks into the app bundle resources.
# The .nnue files are gitignored (109 MB + 3.5 MB); run this after cloning.
set -eu

DIR="$(cd "$(dirname "$0")/.." && pwd)/Chessmaster/Resources/NNUE"
mkdir -p "$DIR"

for NET in nn-c288c895ea92.nnue nn-37f18f62d772.nnue; do
  if [ ! -f "$DIR/$NET" ]; then
    echo "Fetching $NET ..."
    curl -sf -o "$DIR/$NET" "https://data.stockfishchess.org/nn/$NET"
  else
    echo "$NET already present"
  fi
done
echo "NNUE networks ready in $DIR"

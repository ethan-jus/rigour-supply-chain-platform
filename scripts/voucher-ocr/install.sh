#!/usr/bin/env bash
set -euo pipefail
# Run explicitly on the intended host as the Order service account; does not restart services.
if [[ $# -ne 1 ]]; then
  echo 'Usage: install.sh /absolute/path/to/voucher-ocr-venv' >&2
  exit 2
fi
ocr_venv_dir="$1"
if [[ "$ocr_venv_dir" != /* ]]; then
  echo 'The virtual environment path must be absolute.' >&2
  exit 2
fi
ocr_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 -m venv "$ocr_venv_dir"
"$ocr_venv_dir/bin/python" -m pip install -r "$ocr_script_dir/requirements.txt"
"$ocr_venv_dir/bin/python" - <<'PY'
from rapidocr import RapidOCR
import numpy as np
engine = RapidOCR()
engine(np.full((64, 256, 3), 255, dtype=np.uint8))
print('OCR model initialization and CPU inference passed (blank synthetic image).')
PY
printf 'Set RIGOUR_VOUCHER_OCR_PYTHON to %s/bin/python in the Order service environment.\n' "$ocr_venv_dir"

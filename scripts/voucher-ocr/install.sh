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
"${RIGOUR_VOUCHER_OCR_INSTALL_PYTHON:-python3}" -m venv "$ocr_venv_dir"
ocr_requirements="$ocr_script_dir/requirements.txt"
if [[ "${RIGOUR_VOUCHER_OCR_MODEL:-PP-OCRv6}" == "PP-OCRv4" ]]; then
  ocr_requirements="$ocr_script_dir/requirements-centos7.txt"
fi
"$ocr_venv_dir/bin/python" -m pip install -r "$ocr_requirements"
"$ocr_venv_dir/bin/python" - <<'PY'
import os
from rapidocr import RapidOCR, OCRVersion, ModelType
import numpy as np
params = {'EngineConfig.onnxruntime.intra_op_num_threads': 1, 'EngineConfig.onnxruntime.inter_op_num_threads': 1}
if os.environ.get('RIGOUR_VOUCHER_OCR_MODEL') == 'PP-OCRv4':
    params.update({'Det.ocr_version': OCRVersion.PPOCRV4, 'Det.model_type': ModelType.MOBILE,
                   'Rec.ocr_version': OCRVersion.PPOCRV4, 'Rec.model_type': ModelType.MOBILE})
engine = RapidOCR(params=params)
engine(np.full((64, 256, 3), 255, dtype=np.uint8))
engine._load_cls_model()
engine._load_rec_model()
print('OCR model initialization and CPU inference passed (blank synthetic image).')
PY
printf 'Set RIGOUR_VOUCHER_OCR_PYTHON to %s/bin/python in the Order service environment.\n' "$ocr_venv_dir"

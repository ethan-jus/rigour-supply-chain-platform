"""Local OCR only. Extract labeled facts; never infer dates from IDs or receipt records."""
import json
import os
import re
import sys
import unicodedata
from datetime import datetime, timezone, timedelta
from decimal import Decimal

MODEL = os.environ.get('RIGOUR_VOUCHER_OCR_MODEL', 'PP-OCRv6')
VERSION = f'rapidocr-3.10.0/{MODEL}/rules-5'
MIN_SCORE = .96
DATE_MIN_SCORE = .90


def extract(lines):
    def text(row):
        return re.sub(r'\s+', '', row['text'])

    def labeled(labels, min_score=MIN_SCORE, transaction_number=False):
        candidates = []
        for row in lines:
            if row['score'] < min_score:
                continue
            value = text(row)
            # Match the longest label once: 交易流水号 must not also match 交易流水.
            for label in sorted(labels, key=len, reverse=True):
                if not value.startswith(label):
                    continue
                suffix = value[len(label):].lstrip(':：')
                if suffix:
                    candidates.append(suffix)
                    break
                if row['score'] < MIN_SCORE:
                    break
                box = row['box']; x = max(p[0] for p in box); y = sum(p[1] for p in box) / 4
                height = max(p[1] for p in box) - min(p[1] for p in box)
                # Prefer the value on the same row to the right; never search arbitrary nearby digits.
                same = [r for r in lines if r is not row and r['score'] >= min_score
                        and min(p[0] for p in r['box']) >= x - 3
                        and abs(sum(p[1] for p in r['box']) / 4 - y) < max(height * .7, 8)]
                if same:
                    found = min(same, key=lambda r: min(p[0] for p in r['box']))
                    number = text(found)
                    if transaction_number and re.fullmatch(r'[A-Za-z0-9-]{8,128}', number):
                        b = found['box']; left = min(p[0] for p in b); bottom = max(p[1] for p in b)
                        h = bottom - min(p[1] for p in b)
                        # Wrapped IDs continue directly below the value, without another field label.
                        tail = [r for r in lines if re.fullmatch(r'[A-Za-z0-9-]{1,128}', text(r))
                                and abs(min(p[0] for p in r['box']) - left) < h * .6
                                and 0 <= min(p[1] for p in r['box']) - bottom < h * .8
                                and not any(max(p[0] for p in other['box']) < left
                                            and abs(sum(p[1] for p in other['box']) / 4 - sum(p[1] for p in r['box']) / 4) < h * .7
                                            for other in lines if other is not r)]
                        if tail:
                            number = number + text(tail[0]) if len(tail) == 1 and tail[0]['score'] >= min_score else ''
                    candidates.append(number)
                elif transaction_number:
                    # A single bank-transfer row can place its number below the column header.
                    # Multiple numbers in that column are a statement, not one transaction.
                    left = min(p[0] for p in box); bottom = max(p[1] for p in box)
                    below = [r for r in lines if re.fullmatch(r'[A-Za-z0-9-]{8,128}', text(r))
                             and abs(min(p[0] for p in r['box']) - left) < height
                             and min(p[1] for p in r['box']) >= bottom]
                    if len(below) == 1 and below[0]['score'] >= min_score and min(p[1] for p in below[0]['box']) - bottom < height * 2:
                        candidates.append(text(below[0]))
                break
        values = set(candidates)
        return next(iter(values)) if len(values) == 1 else None

    transaction_labels = ('交易单号', '交易流水号', '银行流水号', '交易流水', '交易序号', '网银流水号', '流水号')
    transaction = labeled(transaction_labels, transaction_number=True)
    transaction_needs_review = False
    labels = {text(r) for r in lines if r['score'] >= MIN_SCORE}
    bank_receipt = any('银行' in value and ('回执单' in value or '回单' in value) for value in labels) and {'付款人', '收款人'} <= labels
    if {'交易单号', '转账时间', '收款时间'} <= labels:
        # This transfer layout's scan icon also appears as Q and depresses the line score.
        candidate = labeled(transaction_labels, .95, transaction_number=True)
        icon = re.fullmatch(r'(42\d{26})[@Q]', candidate or '')
        if icon:
            transaction_needs_review = candidate != transaction
            transaction = icon[1]
    # Outside that known layout, remove only @ after a long numeric ID; preserve letters/digits.
    if transaction:
        transaction = re.sub(r'^(\d{20,32})@$', r'\1', transaction)
    if not transaction or not re.fullmatch(r'[A-Za-z0-9-]{8,128}', transaction):
        transaction = None
    # Payment providers also label their transaction reference 订单号. Ordinary sales orders do not qualify.
    payment_bill = {'账单详情', '支付时间', '付款方式'} <= labels and bool({'收单机构', '清算机构'} & labels)
    pos_bill = {'POS编号', '交易类型', '交易状态', '交易时间'} <= labels
    has_primary_reference = any(text(r).startswith(label) for r in lines for label in transaction_labels)
    if not transaction and not has_primary_reference and (payment_bill or pos_bill):
        order_number = labeled(('订单号',), transaction_number=True)
        if order_number and re.fullmatch(r'\d{16,64}', order_number):
            transaction = order_number
    if not transaction and not has_primary_reference and bank_receipt:
        voucher_number = labeled(('凭证号',), transaction_number=True)
        if voucher_number and re.fullmatch(r'[A-Za-z0-9-]{8,128}', voucher_number):
            transaction = voucher_number
    date_labels = ('支付时间', '付款时间', '交易时间', '转账时间', '交易日期', '付款日期')
    if bank_receipt and not any(text(r).startswith(label) for r in lines for label in date_labels):
        date_labels = ('日期',)
    # Date values have a closed calendar format; 90-96% results remain subject to review.
    date_text = labeled(date_labels, DATE_MIN_SCORE)
    date_needs_review = date_text is not None and date_text != labeled(date_labels)
    paid_time = precision = None
    if date_text:
        # OCR may emit full-width colons/digits; normalize typography without guessing digits.
        date_text = unicodedata.normalize('NFKC', date_text)
        date_text = re.sub(r'(\d{1,2})时(\d{1,2})分(?:(\d{1,2})秒)?$',
                           lambda m: f'{int(m[1]):02}:{int(m[2]):02}' + (f':{int(m[3]):02}' if m[3] else ''), date_text)
        m = re.fullmatch(r'(20\d{2})[年/-](\d{1,2})[月/-](\d{1,2})日?(?:(\d{1,2}):(\d{2})(?::(\d{2}))?)?', date_text)
        if m:
            try:
                y, mo, day, h, mi, sec = m.groups()
                d = datetime(int(y), int(mo), int(day), int(h or 0), int(mi or 0), int(sec or 0), tzinfo=timezone(timedelta(hours=8)))
                paid_time = d.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')
                precision = 'SECOND' if sec is not None else 'MINUTE' if mi is not None else 'DAY'
            except ValueError:
                pass
    amount_text = labeled(('付款金额', '支付金额', '转账金额', '交易金额', '实付金额'))
    if not amount_text:
        # Payment-success bill layouts show one signed/currency amount prominently. Require a unique value.
        values = {text(r) for r in lines if r['score'] >= MIN_SCORE
                  and re.fullmatch(r'[-−¥￥][\d,]+\.\d{2}', text(r))}
        amount_text = next(iter(values)) if len(values) == 1 else None
    amount = None
    if amount_text:
        value = re.sub(r'^[-¥￥−]|元$', '', amount_text).replace(',', '')
        if re.fullmatch(r'\d+\.\d{2}', value) and Decimal(value) > 0:
            amount = value
    payee = labeled(('收款方', '收款人', '收款户名', '商户全称'))
    if payee and len(payee) > 255:
        payee = None
    state = labeled(('当前状态', '交易状态', '支付状态', '转账状态'))
    if not state:
        states = {text(r) for r in lines if r['score'] >= MIN_SCORE and text(r) in ('支付成功', '交易成功', '转账成功', '转账失败', '处理中', '已退款')}
        state = next(iter(states)) if len(states) == 1 else None
    payment_result = 'SUCCESS' if state in ('支付成功', '交易成功', '转账成功') else 'NOT_SUCCESS' if state else None
    missing = [label for label, value in [('金额', amount), ('交易单号', transaction), ('付款时间', paid_time), ('收款方', payee)] if not value]
    if payment_result != 'SUCCESS':
        missing.append('支付成功结果')
    if paid_time and date_needs_review:
        missing.append('付款时间置信度偏低')
    if transaction and transaction_needs_review:
        missing.append('交易单号置信度偏低')
    if date_text and not paid_time and re.fullmatch(r'\d{1,2}月\d{1,2}日\d{1,2}:\d{2}(?::\d{2})?', date_text):
        missing.append('付款时间缺少年份（原文：' + date_text + '）')
    return dict(amount=amount, transactionNo=transaction, paidTime=paid_time, paidTimePrecision=precision,
                payee=payee, paymentResult=payment_result, recognitionStatus='REVIEW' if missing else 'RECOGNIZED',
                note=('待核对：' + '、'.join(missing)) if missing else '图片文字识别完成；金额匹配不代表凭证真实性或银行到账已核实',
                engine=VERSION, lines=lines)


def main():
    if sys.argv[1] == '--reparse':
        with open(sys.argv[2], encoding='utf-8') as cached:
            result = extract(json.load(cached)['lines'])
        with open(sys.argv[3], 'w', encoding='utf-8') as target:
            json.dump(result, target, ensure_ascii=False)
        return
    from rapidocr import RapidOCR, OCRVersion, ModelType
    params = {'EngineConfig.onnxruntime.intra_op_num_threads': 1,
              'EngineConfig.onnxruntime.inter_op_num_threads': 1}
    # CentOS 7 uses the compatible v4 mobile models with ONNX Runtime 1.16.3.
    if MODEL == 'PP-OCRv4':
        params.update({'Det.ocr_version': OCRVersion.PPOCRV4, 'Det.model_type': ModelType.MOBILE,
                       'Rec.ocr_version': OCRVersion.PPOCRV4, 'Rec.model_type': ModelType.MOBILE})
    elif MODEL != 'PP-OCRv6':
        raise ValueError('Unsupported voucher OCR model')
    result = RapidOCR(params=params)(sys.argv[1])
    lines = [] if result.txts is None else [dict(text=t, score=float(s), box=b.tolist()) for t, s, b in zip(result.txts, result.scores, result.boxes)]
    with open(sys.argv[2], 'w', encoding='utf-8') as target:
        json.dump(extract(lines), target, ensure_ascii=False)


if __name__ == '__main__':
    main()

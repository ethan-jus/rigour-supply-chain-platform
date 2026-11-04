"""Local OCR only. Extract labeled facts; never infer dates from IDs or receipt records."""
import json
import re
import sys
from datetime import datetime, timezone, timedelta
from decimal import Decimal

VERSION = 'rapidocr-3.10.0/ppocrv6/rules-1'
MIN_SCORE = .96


def extract(lines):
    def text(row):
        return re.sub(r'\s+', '', row['text'])

    def labeled(labels):
        candidates = []
        for row in lines:
            if row['score'] < MIN_SCORE:
                continue
            value = text(row)
            for label in labels:
                if not value.startswith(label):
                    continue
                suffix = value[len(label):].lstrip(':：')
                if suffix:
                    candidates.append(suffix)
                    continue
                box = row['box']; x = max(p[0] for p in box); y = sum(p[1] for p in box) / 4
                height = max(p[1] for p in box) - min(p[1] for p in box)
                # Prefer the value on the same row to the right; never search arbitrary nearby digits.
                same = [r for r in lines if r is not row and r['score'] >= MIN_SCORE
                        and min(p[0] for p in r['box']) >= x - 3
                        and abs(sum(p[1] for p in r['box']) / 4 - y) < max(height * .7, 8)]
                if same:
                    candidates.append(text(min(same, key=lambda r: min(p[0] for p in r['box']))))
        values = set(candidates)
        return next(iter(values)) if len(values) == 1 else None

    transaction = labeled(('交易单号', '交易流水号', '银行流水号'))
    if transaction and not re.fullmatch(r'[A-Za-z0-9-]{8,128}', transaction):
        transaction = None
    date_text = labeled(('支付时间', '付款时间', '交易时间', '转账时间', '交易日期', '付款日期'))
    paid_time = precision = None
    if date_text:
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
    return dict(amount=amount, transactionNo=transaction, paidTime=paid_time, paidTimePrecision=precision,
                payee=payee, paymentResult=payment_result, recognitionStatus='REVIEW' if missing else 'RECOGNIZED',
                note=('待核对：' + '、'.join(missing)) if missing else '图片文字识别完成；金额匹配不代表凭证真实性或银行到账已核实',
                engine=VERSION, lines=lines)


def main():
    from rapidocr import RapidOCR
    result = RapidOCR()(sys.argv[1])
    lines = [] if result.txts is None else [dict(text=t, score=float(s), box=b.tolist()) for t, s, b in zip(result.txts, result.scores, result.boxes)]
    with open(sys.argv[2], 'w', encoding='utf-8') as target:
        json.dump(extract(lines), target, ensure_ascii=False)


if __name__ == '__main__':
    main()

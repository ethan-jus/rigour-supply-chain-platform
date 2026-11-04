import importlib.util
from pathlib import Path
import unittest
source = Path(__file__).resolve().parents[2] / 'main/resources/ocr/recognize_voucher.py'
spec = importlib.util.spec_from_file_location('voucher', source)
voucher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voucher)


def rows(pairs):
    result = []
    for i, (label, value) in enumerate(pairs):
        for x, text in ((0, label), (200, value)):
            result.append(dict(text=text, score=.99, box=[[x, i*40], [x+150, i*40], [x+150, i*40+20], [x, i*40+20]]))
    return result


class VoucherRulesTest(unittest.TestCase):
    def test_exact_labels_and_business_timezone(self):
        r = voucher.extract(rows([('付款金额','￥156.00'),('支付时间','2026年10月10日20:37:52'),('交易单号','1234567890123456'),('商户单号','9999999999'),('商户全称','公司'),('当前状态','支付成功')]))
        self.assertEqual(r['transactionNo'], '1234567890123456')
        self.assertEqual(r['paidTime'], '2026-10-10T12:37:52Z')
        self.assertEqual(r['recognitionStatus'], 'RECOGNIZED')

    def test_header_time_and_transaction_digits_never_become_payment_date(self):
        r = voucher.extract(rows([('手机时间','14:35'),('交易单号','4500000427202610101393333712')]))
        self.assertIsNone(r['paidTime'])
        self.assertEqual(r['recognitionStatus'], 'REVIEW')

    def test_precision_and_invalid_calendar_date(self):
        for text, precision in [('2026-10-10','DAY'),('2026/10/10 20:37','MINUTE')]:
            self.assertEqual(voucher.extract(rows([('付款时间',text)]))['paidTimePrecision'], precision)
        self.assertIsNone(voucher.extract(rows([('付款时间','2026年2月30日')]))['paidTime'])

    def test_conflicting_numbers_low_confidence_and_non_success_remain_review(self):
        data=rows([('交易单号','123456789'),('交易单号','987654321'),('付款金额','100.00'),('支付时间','2026-10-10'),('当前状态','处理中')])
        r=voucher.extract(data)
        self.assertIsNone(r['transactionNo'])
        self.assertEqual(r['paymentResult'],'NOT_SUCCESS')
        self.assertEqual(r['recognitionStatus'],'REVIEW')
        data=rows([('交易单号','123456789')]);data[1]['score']=.8
        self.assertIsNone(voucher.extract(data)['transactionNo'])


if __name__ == '__main__': unittest.main()

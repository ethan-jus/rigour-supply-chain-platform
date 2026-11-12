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
    def test_payment_bill_order_number_is_transaction_reference(self):
        data = rows([('账单详情', ''), ('支付时间', '2026-11-04 23:36:35'),
                     ('付款方式', '账户余额'), ('收单机构', '中国建设银行'), ('清算机构', '中国银联'),
                     ('订单号', '2026110423001426891439785414'), ('商家订单号', '0920******'),
                     ('交易状态', '交易成功')])
        r = voucher.extract(data)
        self.assertEqual(r['transactionNo'], '2026110423001426891439785414')
        self.assertEqual(r['paidTime'], '2026-11-04T15:36:35Z')
        self.assertEqual(r['paymentResult'], 'SUCCESS')
        data[10]['text'] = '订单号：2026110423001426891439785414'
        del data[11]
        self.assertEqual(voucher.extract(data)['transactionNo'], '2026110423001426891439785414')

    def test_pos_payment_order_preserves_leading_zeroes(self):
        data = rows([('交易时间', '2026/08/06 19:21:24'), ('POS编号', '1050001794150640001'),
                     ('交易类型', '微信支付'), ('交易状态', '成功'), ('订单号', '0320260806001157074187')])
        self.assertEqual(voucher.extract(data)['transactionNo'], '0320260806001157074187')

    def test_generic_orders_and_merchant_orders_are_not_payment_references(self):
        for number in ['DH.20260907.0347', '2026110423001426891439785414']:
            data = rows([('订单详情', ''), ('下单时间', '2026-11-04 23:36:35'),
                         ('订单号', number), ('付款方式', '账户余额'), ('交易状态', '交易成功')])
            self.assertIsNone(voucher.extract(data)['transactionNo'])
        context = rows([('账单详情', ''), ('支付时间', '2026-11-04 23:36:35'),
                        ('付款方式', '账户余额'), ('清算机构', '中国银联')])
        self.assertIsNone(voucher.extract(context + rows([('商家订单号', '2026110423001426891439785414')]))['transactionNo'])
        for invalid in ['0920******', 'DH.20260907.0347', '12345678']:
            self.assertIsNone(voucher.extract(context + rows([('订单号：' + invalid, '')])[:1])['transactionNo'])

    def test_bill_order_fallback_requires_clear_unique_value_and_no_primary_reference(self):
        context = rows([('账单详情', ''), ('支付时间', '2026-11-04 23:36:35'),
                        ('付款方式', '账户余额'), ('清算机构', '中国银联')])
        order = dict(text='订单号：2026110423001426891439785414', score=.99,
                     box=[[0,200],[500,200],[500,220],[0,220]])
        self.assertEqual(voucher.extract(context + [order])['transactionNo'], '2026110423001426891439785414')
        order['score'] = .95
        self.assertIsNone(voucher.extract(context + [order])['transactionNo'])
        order['score'] = .99
        second = {**order, 'text':'订单号：2026110423001426891439785415'}
        self.assertIsNone(voucher.extract(context + [order, second])['transactionNo'])
        for primary in ['交易单号：1234567890', '交易单号：****', '交易单号：1234567890@']:
            r = voucher.extract(context + [order, {**order, 'text':primary}])
            self.assertEqual(r['transactionNo'], '1234567890' if primary == '交易单号：1234567890' else None)

    def test_bank_transaction_labels_preserve_letters_and_leading_zeroes(self):
        for label, number in [('交易流水', 'C4147K3000651HZ'),
                              ('交易流水号', 'C4147K3000651HZ'),
                              ('交易序号', 'HQP932062238749'),
                              ('网银流水号', '002453398863'),
                              ('流水号', '20261029024276829800001')]:
            with self.subTest(label=label):
                self.assertEqual(voucher.extract(rows([(label, number)]))['transactionNo'], number)
                inline = rows([(label + '：' + number, '')])[:1]
                self.assertEqual(voucher.extract(inline)['transactionNo'], number)

    def test_wechat_scan_icon_is_not_part_of_transaction_number(self):
        number = '4200003210202611031375780193'
        for suffix in ['@', ' @']:
            data = rows([('交易单号', number + suffix)])
            data[1]['score'] = .96833
            self.assertEqual(voucher.extract(data)['transactionNo'], number)
        for invalid in ['12345678@', 'C4147K3000651HZ@', number + '@@', number[:10] + '@' + number[10:]]:
            self.assertIsNone(voucher.extract(rows([('交易单号', invalid)]))['transactionNo'])
        self.assertEqual(voucher.extract(rows([('交易流水', 'C4147K3000651HZ')]))['transactionNo'], 'C4147K3000651HZ')

    def test_lower_confidence_scan_icon_requires_wechat_transfer_layout(self):
        number = '4200003246202610275323422597'
        for icon in [' @', ' Q']:
            data = rows([('交易单号', number + icon), ('转账时间', '2026年10月27日17:43:47'),
                         ('收款时间', '2026年10月27日17:44:39')])
            data[1]['score'] = .95673
            r = voucher.extract(data)
            self.assertEqual(r['transactionNo'], number)
            self.assertEqual(r['recognitionStatus'], 'REVIEW')
            self.assertIn('交易单号置信度偏低', r['note'])
            data[1]['score'] = .949
            self.assertIsNone(voucher.extract(data)['transactionNo'])
            data[1]['score'] = .95673
            self.assertIsNone(voucher.extract(data[:2])['transactionNo'])
        self.assertEqual(voucher.extract(rows([('交易单号', number + 'Q')]))['transactionNo'], number + 'Q')

    def test_business_references_are_not_transaction_numbers(self):
        data = rows([('业务编号', '54S077K1000009043449'), ('业务参考号', '20261024154605'),
                     ('回单编号', '854B7A0059014'), ('商户单号', '1234567890123'),
                     ('客户编号', '1234567890123')])
        self.assertIsNone(voucher.extract(data)['transactionNo'])
        data.extend(rows([('交易流水：C4147K3000651HZ', '')])[:1])
        self.assertEqual(voucher.extract(data)['transactionNo'], 'C4147K3000651HZ')
        data.extend(rows([('交易流水号：C4147K3000651HA', '')])[:1])
        self.assertIsNone(voucher.extract(data)['transactionNo'])

    def test_single_bank_table_value_below_header_but_not_multi_transaction_statement(self):
        def cell(value, x, y, width=180, score=.99):
            return dict(text=value, score=score, box=[[x,y],[x+width,y],[x+width,y+24],[x,y+24]])
        data = [cell('交易流水号', 1006, 1591, 75), cell('2026073002105458619956', 1003, 1622),
                cell('11050171360000002801', 700, 1622)]
        self.assertEqual(voucher.extract(data)['transactionNo'], '2026073002105458619956')
        data.append(cell('2026073002105458619957', 1003, 1700))
        self.assertIsNone(voucher.extract(data)['transactionNo'])
        data[-1]['score'] = .94
        self.assertIsNone(voucher.extract(data)['transactionNo'])
        for x,y,score in [(1003,1700,.99),(700,1622,.99),(1003,1622,.95)]:
            self.assertIsNone(voucher.extract([data[0],cell('2026073002105458619956',x,y,score=score)])['transactionNo'])

    def test_exact_labels_and_business_timezone(self):
        r = voucher.extract(rows([('付款金额','￥156.00'),('支付时间','2026年10月10日20:37:52'),('交易单号','1234567890123456'),('商户单号','9999999999'),('商户全称','公司'),('当前状态','支付成功')]))
        self.assertEqual(r['transactionNo'], '1234567890123456')
        self.assertEqual(r['paidTime'], '2026-10-10T12:37:52Z')
        self.assertEqual(r['recognitionStatus'], 'RECOGNIZED')

    def test_wrapped_transaction_number_requires_clear_unlabeled_continuation(self):
        data = rows([('交易单号', '4500000540202610287587')])
        tail = dict(text='183290',score=.99999,box=[[200,26],[250,26],[250,46],[200,46]])
        data.append(tail)
        self.assertEqual(voucher.extract(data)['transactionNo'], '4500000540202610287587183290')
        tail['score'] = .94
        self.assertIsNone(voucher.extract(data)['transactionNo'])
        tail['score'] = .99999
        data.append(dict(text='商户单号',score=.99,box=[[0,26],[190,26],[190,46],[0,46]]))
        self.assertEqual(voucher.extract(data)['transactionNo'], '4500000540202610287587')

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

    def test_v4_payment_dates_below_general_threshold_are_visible_but_need_review(self):
        for date, score, expected in [
            ('2026年11月06日17:53:41', .95798, '2026-11-06T09:53:41Z'),
            ('2026年11月6日20:55:22', .95203, '2026-11-06T12:55:22Z'),
            ('2026年11月7日15:09:25', .94883, '2026-11-07T07:09:25Z'),
            ('2026年10月31日22:32:09', .92911, '2026-10-31T14:32:09Z'),
        ]:
            with self.subTest(date=date):
                data = rows([('支付时间', date), ('付款金额', '156.00'),
                             ('交易单号', '1234567890'), ('商户全称', '测试公司'),
                             ('当前状态', '支付成功')])
                data[1]['score'] = score
                r = voucher.extract(data)
                self.assertEqual(r['paidTime'], expected)
                self.assertEqual(r['paidTimePrecision'], 'SECOND')
                self.assertEqual(r['recognitionStatus'], 'REVIEW')
                self.assertIn('付款时间置信度偏低', r['note'])

    def test_full_width_colon_and_transfer_time_are_not_receipt_time(self):
        data = rows([('转账时间', '2026年11月06日19:45：13'),
                     ('收款时间', '2026年11月06日19:45:57')])
        data[1]['score'] = .96413
        self.assertEqual(voucher.extract(data)['paidTime'], '2026-11-06T11:45:13Z')
        self.assertIsNone(voucher.extract(rows([('收款时间', '2026年11月06日19:45:57')]))['paidTime'])

    def test_normalizes_full_width_date_digits_without_guessing_missing_digits(self):
        self.assertEqual(voucher.extract(rows([('付款时间', '２０２６／１１／０６ １９：４５：１３')]))['paidTime'],
                         '2026-11-06T11:45:13Z')
        for invalid in ['2026年11月06日19:45:1O', '2026年2月30日19：45：13', '2026年11月06日24：01：00']:
            with self.subTest(date=invalid):
                self.assertIsNone(voucher.extract(rows([('付款时间', invalid)]))['paidTime'])

    def test_date_threshold_does_not_relax_amount_or_transaction_number(self):
        data = rows([('支付时间', '2026年11月06日17:53:41'),
                     ('付款金额', '156.00'), ('交易单号', '1234567890')])
        for row in data[1::2]:
            row['score'] = .95
        r = voucher.extract(data)
        self.assertEqual(r['paidTime'], '2026-11-06T09:53:41Z')
        self.assertIsNone(r['amount'])
        self.assertIsNone(r['transactionNo'])
        data[1]['score'] = .89999
        self.assertIsNone(voucher.extract(data)['paidTime'])

    def test_chinese_bank_receipt_time_preserves_actual_precision(self):
        for value, expected, precision in [
            ('2026年10月30日14时55分', '2026-10-30T06:55:00Z', 'MINUTE'),
            ('2026年10月30日14时5分3秒', '2026-10-30T06:05:03Z', 'SECOND'),
        ]:
            with self.subTest(value=value):
                r = voucher.extract(rows([('交易时间', value)]))
                self.assertEqual(r['paidTime'], expected)
                self.assertEqual(r['paidTimePrecision'], precision)

    def test_missing_year_relative_dates_and_other_business_dates_are_not_inferred(self):
        for value in ['10月21日下午5:59', '10月12日18:04', '今天01:05']:
            self.assertIsNone(voucher.extract(rows([('交易时间', value)]))['paidTime'])
        for label in ['下单时间', '创建时间', '一次批复时间', '经办时间', '期望时间', '销售日期']:
            self.assertIsNone(voucher.extract(rows([(label, '2026-10-10 12:00:00')]))['paidTime'])

    def test_lower_date_value_threshold_still_requires_clear_separate_label(self):
        data = rows([('支付时间', '2026年11月06日17:53:41')])
        data[0]['score'] = .94
        self.assertIsNone(voucher.extract(data)['paidTime'])
        data[0]['score'] = .99
        data[1]['score'] = .90
        self.assertEqual(voucher.extract(data)['paidTime'], '2026-11-06T09:53:41Z')
        self.assertEqual(voucher.extract(data)['recognitionStatus'], 'REVIEW')

    def test_conflicting_labeled_payment_dates_are_not_chosen_arbitrarily(self):
        data = rows([('支付时间', '2026年11月06日17:53:41'),
                     ('转账时间', '2026年11月06日17:53:42')])
        data[3]['score'] = .95203
        self.assertIsNone(voucher.extract(data)['paidTime'])

    def test_inline_date_label_accepts_ocr_colon_but_not_unlabeled_dates(self):
        data = [dict(text='支付时间：2026年11月06日17：53：41', score=.95203,
                     box=[[0, 0], [400, 0], [400, 20], [0, 20]])]
        self.assertEqual(voucher.extract(data)['paidTime'], '2026-11-06T09:53:41Z')
        data[0]['text'] = '2026年11月06日17：53：41'
        self.assertIsNone(voucher.extract(data)['paidTime'])


    def test_bank_receipt_voucher_reference_and_date_require_receipt_context(self):
        context = rows([('中国建设银行网上银行电子回执单', ''), ('付款人', ''), ('收款人', '')])
        fields = rows([('凭证号', '108777751957'), ('日期', '2026-11-10 15:19'), ('交易状态', '银行受理成功')])
        r = voucher.extract(rows([('中国建设银行网上银行电子回执单', ''), ('付款人', ''), ('收款人', ''), ('凭证号', '108777751957'), ('日期', '2026-11-10 15:19'), ('交易状态', '银行受理成功')]))
        self.assertEqual(r['transactionNo'], '108777751957')
        self.assertEqual(r['paidTime'], '2026-11-10T07:19:00Z')
        self.assertEqual(r['paidTimePrecision'], 'MINUTE')
        self.assertEqual(r['paymentResult'], 'NOT_SUCCESS')
        for subset in (fields, rows([('销售凭证', '')]) + fields):
            self.assertIsNone(voucher.extract(subset)['transactionNo'])
            self.assertIsNone(voucher.extract(subset)['paidTime'])
        self.assertIsNone(voucher.extract(context + fields + rows([('交易单号', '****')]))['transactionNo'])

    def test_missing_year_is_explained_without_inventing_year_from_transaction(self):
        r = voucher.extract(rows([('转账时间', '11月10日20:04'), ('交易单号', '4200003222202611104881048920')]))
        self.assertIsNone(r['paidTime'])
        self.assertIn('付款时间缺少年份', r['note'])

    def test_cached_ocr_lines_reparse_without_loading_image_model(self):
        import json, subprocess, sys, tempfile
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / 'input.json'; target = Path(directory) / 'result.json'
            original.write_text(json.dumps(dict(lines=rows([('支付时间', '2026年11月10日22:19:33')]))))
            subprocess.run([sys.executable, '-S', str(source), '--reparse', str(original), str(target)], check=True)
            r = json.loads(target.read_text())
            self.assertEqual(r['paidTime'], '2026-11-10T14:19:33Z')
            self.assertTrue(r['engine'].endswith('/rules-5'))


if __name__ == '__main__': unittest.main()

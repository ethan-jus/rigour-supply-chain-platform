"""首次业务关联的歧义、稳定性、复跑与写入前置条件回归。"""
import copy
import unittest
from unittest.mock import MagicMock
from business_links import candidates, apply_plan, build_plan


def person(source_id='S1', name='张三', city='武汉', state='在职'):
    return dict(source_id=source_id, name=name, city=city, employment_status=state)


def employee(code='E1', name='张三', city='武汉', state='ACTIVE'):
    return dict(target_id=code, target_code=code, name=name, city=city, employment_status=state)


def store(source_id='S1', name='中心店', city='武汉', phone=None, address=None):
    return dict(source_id=source_id, name=name, city=city, status='ACTIVE', contact_phone=phone,
                location_address=address, location_formatted_address=None, source_poi_address=None)


def customer(code=1, name='中心店', city='武汉', phone=None, address=None):
    return dict(target_id=code, target_code='C' + str(code), name=name, city=city, status='ACTIVE',
                contact_phone=phone, address=address)


class BusinessLinksTest(unittest.TestCase):
    def reason(self, kind, sources, targets, existing=()):
        return candidates(kind, sources, targets, existing)['review'][0]['reason']

    def test_exact_trimmed_employee_name_city_status(self):
        result = candidates('employee', [person(name=' 张三 ')], [employee()], [])
        self.assertEqual('E1', result['eligible'][0]['target_code'])
        self.assertEqual(' 张三 ', result['eligible'][0]['evidence']['source_name'])

    def test_employment_status_and_city_conflicts_stay_unlinked(self):
        self.assertEqual('EMPLOYMENT_STATUS_CONFLICT', self.reason('employee', [person()], [employee(state='LEFT')]))
        self.assertEqual('EMPLOYMENT_STATUS_CONFLICT', self.reason('employee', [person(state='待确认')], [employee(state='PENDING')]))
        self.assertEqual('CITY_CONFLICT', self.reason('employee', [person()], [employee(city='上海')]))

    def test_duplicate_employee_names_even_across_cities_are_ambiguous(self):
        self.assertEqual('MULTIPLE_TARGETS', self.reason('employee', [person()], [employee(), employee('E2', city='上海')]))
        self.assertEqual('MULTIPLE_SOURCES', self.reason('employee', [person(), person('S2', city='上海')], [employee()]))

    def test_store_requires_both_sides_unique_in_city(self):
        self.assertEqual('MULTIPLE_SOURCES', self.reason('customer', [store(), store('S2')], [customer()]))
        self.assertEqual('MULTIPLE_TARGETS', self.reason('customer', [store()], [customer(), customer(2)]))
        self.assertEqual(1, len(candidates('customer', [store()], [customer(), customer(2, city='上海')], [])['eligible']))

    def test_no_fuzzy_chain_branch_case_or_city_suffix_matching(self):
        for name in ['中心店(武昌)', '中心 店', '中心门店']:
            self.assertEqual('NO_TARGET_MATCH', self.reason('customer', [store()], [customer(name=name)]))
        self.assertEqual('NO_TARGET_MATCH', self.reason('customer', [store()], [customer(city='武汉市')]))

    def test_address_and_phone_conflicts_and_missing_evidence(self):
        self.assertEqual('PHONE_CONFLICT', self.reason('customer', [store(phone='111')], [customer(phone='222')]))
        self.assertEqual('ADDRESS_CONFLICT', self.reason('customer', [store(address='路1号')], [customer(address='路2号')]))
        result = candidates('customer', [store()], [customer()], [])
        self.assertFalse(result['eligible'][0]['evidence']['phone_corroborated'])
        self.assertFalse(result['eligible'][0]['evidence']['address_corroborated'])

    def test_address_can_match_one_of_recorded_addresses(self):
        row = store(address='旧地址'); row['source_poi_address'] = '新地址'
        self.assertEqual(1, len(candidates('customer', [row], [customer(address='新地址')], [])['eligible']))

    def test_existing_link_survives_rename_city_status_changes(self):
        existing = [dict(source_id='S1', target_id='E1')]
        result = candidates('employee', [person(name='改名', city='上海', state='离职')], [employee()], existing)
        self.assertEqual([], result['eligible'])
        self.assertEqual([], result['review'])
        self.assertEqual(1, result['existing'])

    def test_bound_target_cannot_be_taken_by_new_source(self):
        self.assertEqual('TARGET_ALREADY_LINKED', self.reason('employee', [person('S2')], [employee()], [dict(source_id='S1', target_id='E1')]))

    def test_inactive_and_blank_values_never_auto_link(self):
        c = customer(); c['status'] = 'INACTIVE'
        self.assertEqual('INACTIVE_STORE_OR_CUSTOMER', self.reason('customer', [store()], [c]))
        self.assertEqual('MISSING_NAME_OR_CITY', self.reason('customer', [store(name=' ')], [customer(name=' ')]))

    def test_digest_binds_tenant_entire_input_and_existing_links(self):
        rows = dict(people=[person()], stores=[], hr=[employee()], crm=[], employee_links=[], customer_links=[])
        plan = build_plan('T', rows)
        changed = copy.deepcopy(rows); changed['hr'][0]['employment_status'] = 'LEFT'
        self.assertNotEqual(plan['input_digest'], build_plan('T', changed)['input_digest'])
        self.assertNotEqual(plan['input_digest'], build_plan('OTHER', rows)['input_digest'])
        conn = MagicMock()
        with self.assertRaises(ValueError):
            apply_plan(conn, plan, 'old-digest', 'operator')
        conn.cursor.assert_not_called()


if __name__ == '__main__':
    unittest.main()

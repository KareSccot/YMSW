from __future__ import annotations

import unittest
from urllib.parse import parse_qs, urlparse

from maas_usage_sync.config import YiDaConfig
from maas_usage_sync.yida import (
    YiDaClient,
    build_consumer_project_mapping_rows,
    build_instance_id_payload,
    build_process_instances_payload,
    consumer_from_email,
    consumer_from_project_id,
    extract_instance_ids,
    extract_approval_result,
    extract_process_instances,
    extract_total_count,
    flatten_yida_instance,
    flatten_yida_instance_raw,
    normalize_approval_result,
)


def make_yida_config(**overrides) -> YiDaConfig:
    values = {
        "app_type": "APP_1",
        "form_uuid": "FORM_1",
        "system_token": "system_token_1",
        "user_id": "user_1",
        "language": "zh_CN",
        "use_alias": True,
        "search_field_json": "",
        "page_size": 100,
    }
    values.update(overrides)
    return YiDaConfig(**values)


class YiDaTests(unittest.TestCase):
    def test_instance_ids_url_matches_yida_document(self) -> None:
        client = YiDaClient(make_yida_config(app_type="APP 1", form_uuid="FORM/1"), access_token="token")

        url = client._instance_ids_url(page_number=2, page_size=50)
        parsed = urlparse(url)
        query = parse_qs(parsed.query)

        self.assertEqual(parsed.scheme, "https")
        self.assertEqual(parsed.netloc, "api.dingtalk.com")
        self.assertEqual(parsed.path, "/v2.0/yida/forms/instances/ids/APP%201/FORM%2F1")
        self.assertEqual(query["pageNumber"], ["2"])
        self.assertEqual(query["pageSize"], ["50"])

    def test_build_instance_id_payload_uses_required_fields(self) -> None:
        payload = build_instance_id_payload(make_yida_config())

        self.assertEqual(
            payload,
            {
                "systemToken": "system_token_1",
                "userId": "user_1",
                "language": "zh_CN",
                "useAlias": True,
            },
        )

    def test_build_instance_id_payload_includes_search_field_json_when_configured(self) -> None:
        payload = build_instance_id_payload(
            make_yida_config(search_field_json='{"selectField_mov119ub":"获取API高代码开发"}')
        )

        self.assertEqual(payload["searchFieldJson"], '{"selectField_mov119ub":"获取API高代码开发"}')

    def test_process_instances_url_matches_yida_document(self) -> None:
        client = YiDaClient(make_yida_config(), access_token="token")

        url = client._process_instances_url(page_number=3, page_size=100)
        parsed = urlparse(url)
        query = parse_qs(parsed.query)

        self.assertEqual(parsed.scheme, "https")
        self.assertEqual(parsed.netloc, "api.dingtalk.com")
        self.assertEqual(parsed.path, "/v2.0/yida/processes/instances")
        self.assertEqual(query["pageNumber"], ["3"])
        self.assertEqual(query["pageSize"], ["100"])

    def test_build_process_instances_payload_uses_required_fields(self) -> None:
        payload = build_process_instances_payload(
            make_yida_config(search_field_json='{"selectField_mov119ub":"获取API高代码开发"}'),
            approved_result="agree",
        )

        self.assertEqual(payload["appType"], "APP_1")
        self.assertEqual(payload["systemToken"], "system_token_1")
        self.assertEqual(payload["userId"], "user_1")
        self.assertEqual(payload["language"], "zh_CN")
        self.assertEqual(payload["formUuid"], "FORM_1")
        self.assertEqual(payload["useAlias"], True)
        self.assertEqual(payload["approvedResult"], "agree")
        self.assertEqual(payload["searchFieldJson"], '{"selectField_mov119ub":"获取API高代码开发"}')

    def test_extract_instance_ids_supports_common_response_shapes(self) -> None:
        self.assertEqual(extract_instance_ids({"result": ["FORM_INST_1", "FORM_INST_2"]}), ["FORM_INST_1", "FORM_INST_2"])
        self.assertEqual(
            extract_instance_ids({"data": {"formInstanceIdList": ["FORM_INST_3"]}}),
            ["FORM_INST_3"],
        )

    def test_extract_process_instances_and_total_count(self) -> None:
        payload = {
            "totalCount": 2,
            "data": [
                {"processInstanceId": "PROC_1"},
                {"processInstanceId": "PROC_2"},
            ],
        }

        self.assertEqual(extract_total_count(payload), 2)
        self.assertEqual(
            extract_process_instances(payload),
            [{"processInstanceId": "PROC_1"}, {"processInstanceId": "PROC_2"}],
        )
        self.assertEqual(
            extract_instance_ids({"value": [{"id": "FORM_INST_4"}, {"formInstanceId": "FORM_INST_5"}]}),
            ["FORM_INST_4", "FORM_INST_5"],
        )

    def test_flatten_yida_instance_extracts_mapping_fields(self) -> None:
        row = flatten_yida_instance(
            {
                "modifiedTimeGMT": "2026-07-09T13:47Z",
                "formInstId": "21721ebf-2e80-4642-a71f-6dfe00083996",
                "formData": {
                    "selectField_mov119ub": "DEAP",
                    "departmentSelectField_mov119t9": ["部门 A"],
                    "departmentSelectField_mov119t9_id": ["1048070109"],
                    "employeeField_mov119ta": ["滕涛"],
                    "employeeField_mov119ta_id": ["30010575"],
                    "textField_mp0l8em5": "30010575",
                    "textField_mov119tc": "AI-30010575-1783308619614",
                    "textField_mpp86wex": "c9JLYW1BnJAq0WowDgIRSwiEiE",
                    "textField_mov119tq": "XBCOE control trending data自动提取录入",
                    "textareaField_moxxwwal": "project_TT Risk Assessment_test",
                    "serialNumberField_mov119tl": "C202607090307",
                },
                "originator": {"userId": "30010575"},
            }
        )

        self.assertEqual(row["form_inst_id"], "21721ebf-2e80-4642-a71f-6dfe00083996")
        self.assertEqual(row["serial_no"], "C202607090307")
        self.assertEqual(row["development_method"], "DEAP")
        self.assertEqual(row["project_code"], "AI-30010575-1783308619614")
        self.assertEqual(row["project_name"], "XBCOE control trending data自动提取录入")
        self.assertEqual(row["applicant_user_id"], "30010575")
        self.assertEqual(row["applicant_name"], "滕涛")
        self.assertEqual(row["department_id"], "1048070109")
        self.assertEqual(row["department_name"], "部门 A")
        self.assertEqual(row["api_key_or_consumer_candidate"], "c9JLYW1BnJAq0WowDgIRSwiEiE")
        self.assertEqual(row["consumer_candidate_from_yida"], "project_TT Risk Assessment_test")

    def test_flatten_yida_instance_supports_process_instance_data(self) -> None:
        row = flatten_yida_instance(
            {
                "modifiedTimeGMT": "2026-07-08T11:20Z",
                "processInstanceId": "90187f1e-c4f1-41a7-9c2b-43779e118fa6",
                "data": {
                    "selectField_mov119ub": "获取API高代码开发",
                    "textField_mov119tc": "AI-30020995-1781236860030",
                    "textField_mov119tq": "TT Risk Asssement AI Agent",
                    "textareaField_moxxwwal": "project_TT Risk Assessment_test",
                },
                "originator": {"userId": "30020995"},
            }
        )

        self.assertEqual(row["form_inst_id"], "90187f1e-c4f1-41a7-9c2b-43779e118fa6")
        self.assertEqual(row["project_code"], "AI-30020995-1781236860030")
        self.assertEqual(row["project_name"], "TT Risk Asssement AI Agent")
        self.assertEqual(row["consumer_candidate_from_yida"], "project_TT Risk Assessment_test")

    def test_flatten_yida_instance_raw_exports_all_form_data_fields(self) -> None:
        row = flatten_yida_instance_raw(
            {
                "modifiedTimeGMT": "2026-07-09T13:47Z",
                "formInstId": "FORM_INST_1",
                "formData": {
                    "textField_a": "hello",
                    "employeeField_b": ["张三"],
                    "tableField_c": [{"name": "row1"}],
                },
                "originator": {"userId": "30010575"},
            }
        )

        self.assertEqual(row["form_inst_id"], "FORM_INST_1")
        self.assertEqual(row["modified_time"], "2026-07-09T13:47Z")
        self.assertEqual(row["originator_user_id"], "30010575")
        self.assertEqual(row["formData.textField_a"], "hello")
        self.assertEqual(row["formData.employeeField_b"], '["张三"]')
        self.assertEqual(row["formData.tableField_c"], '[{"name":"row1"}]')

    def test_consumer_from_email_uses_mail_prefix_and_replaces_dots(self) -> None:
        self.assertEqual(consumer_from_email("xu.dening@wuxibiologics.com"), "xu_dening")

    def test_consumer_from_project_id_replaces_spaces_and_hyphens(self) -> None:
        self.assertEqual(consumer_from_project_id("project TT Risk-Assessment test"), "project_TT_Risk_Assessment_test")

    def test_build_consumer_project_mapping_rows_expands_users_and_projects(self) -> None:
        rows = build_consumer_project_mapping_rows(
            {
                "modifiedTimeGMT": "2026-07-09T13:47Z",
                "formInstId": "FORM_INST_1",
                "approvalResult": "同意",
                "formData": {
                    "serialNumberField_mov119tl": "C202607090307",
                    "selectField_mowgdwer": "已通过",
                    "selectField_mov119ub": "获取API高代码开发",
                    "textareaField_moxxwwai": "xu.dening@wuxibiologics.com\nwang.wenhuan@wuxibiologics.com",
                    "textareaField_moxxwwal": "project TT Risk Assessment test",
                    "textField_mov119tc": "AI-30010575-1783308619614",
                    "textField_mov119tq": "XBCOE control trending data自动提取录入",
                    "departmentSelectField_mov119tb": ["项目归属部门 A"],
                    "employeeField_mov119t8": ["申请人 A"],
                    "departmentSelectField_mov119t9": ["申请人部门 A"],
                    "employeeField_mov119ta": ["项目负责人 A"],
                },
            }
        )

        self.assertEqual([row["consumer"] for row in rows], ["xu_dening", "wang_wenhuan", "project_TT_Risk_Assessment_test"])
        self.assertEqual([row["consumer_type"] for row in rows], ["personal", "personal", "project"])
        self.assertEqual(rows[0]["approval_result"], "同意")
        self.assertEqual(rows[0]["consumer_source"], "provided_user_list")
        self.assertEqual(rows[2]["consumer_source"], "provided_project_id_list")
        self.assertEqual(rows[0]["project_code"], "AI-30010575-1783308619614")
        self.assertEqual(rows[0]["project_department_name"], "项目归属部门 A")
        self.assertEqual(rows[0]["applicant_name"], "申请人 A")
        self.assertEqual(rows[0]["applicant_department_name"], "申请人部门 A")
        self.assertEqual(rows[0]["project_owner_name"], "项目负责人 A")

    def test_extract_approval_result_supports_nested_process_result(self) -> None:
        self.assertEqual(
            extract_approval_result({"processInstance": {"approvalResult": "同意"}}),
            "同意",
        )

    def test_normalize_approval_result_translates_process_api_values(self) -> None:
        self.assertEqual(normalize_approval_result("agree"), "同意")
        self.assertEqual(normalize_approval_result("reject"), "拒绝")
        self.assertEqual(normalize_approval_result("同意"), "同意")

    def test_build_consumer_project_mapping_rows_supports_process_instance(self) -> None:
        rows = build_consumer_project_mapping_rows(
            {
                "modifiedTimeGMT": "2026-07-08T11:20Z",
                "processInstanceId": "90187f1e-c4f1-41a7-9c2b-43779e118fa6",
                "approvedResult": "agree",
                "data": {
                    "selectField_mowgdwer": "已申请",
                    "selectField_mov119ub": "获取API高代码开发",
                    "textareaField_moxxwwai": "yang.haolin@wuxibiologics.com",
                    "textareaField_moxxwwal": "project_TT Risk Assessment_test",
                    "textField_mov119tc": "AI-30020995-1781236860030",
                    "textField_mov119tq": "TT Risk Asssement AI Agent",
                    "departmentSelectField_mov119tb": ["项目归属部门 A"],
                    "employeeField_mov119t8": ["申请人 A"],
                    "departmentSelectField_mov119t9": ["申请人部门 A"],
                    "employeeField_mov119ta": ["项目负责人 A"],
                },
            },
            required_approval_result="同意",
        )

        self.assertEqual([row["consumer"] for row in rows], ["yang_haolin", "project_TT_Risk_Assessment_test"])
        self.assertEqual(rows[0]["form_inst_id"], "90187f1e-c4f1-41a7-9c2b-43779e118fa6")
        self.assertEqual(rows[0]["approval_result"], "同意")

    def test_build_consumer_project_mapping_rows_filters_by_approval_result(self) -> None:
        payload = {
            "modifiedTimeGMT": "2026-07-09T13:47Z",
            "formInstId": "FORM_INST_1",
            "approvalResult": "拒绝",
            "formData": {
                "textareaField_moxxwwai": "xu.dening@wuxibiologics.com",
                "textareaField_moxxwwal": "project TT Risk Assessment test",
            },
        }

        self.assertEqual(
            build_consumer_project_mapping_rows(payload, required_approval_result="同意"),
            [],
        )


if __name__ == "__main__":
    unittest.main()

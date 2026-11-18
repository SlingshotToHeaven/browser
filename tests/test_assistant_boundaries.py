import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class AssistantBoundaryConfigurationTest(unittest.TestCase):
    def test_alarm_receiver_not_exported_and_no_exact_alarm_special_access(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        permissions = {item.get(ANDROID + 'name') for item in manifest.findall('uses-permission')}
        self.assertIn('android.permission.RECEIVE_BOOT_COMPLETED', permissions)
        self.assertTrue(permissions.isdisjoint({'android.permission.USE_EXACT_ALARM', 'android.permission.SCHEDULE_EXACT_ALARM'}))
        receivers = [item for item in manifest.findall('application/receiver') if 'AutomationAlarmReceiver' in item.get(ANDROID + 'name', '')]
        self.assertEqual(1, len(receivers))
        self.assertEqual('false', receivers[0].get(ANDROID + 'exported'))
        self.assertEqual({'android.intent.action.BOOT_COMPLETED', 'android.intent.action.TIME_SET',
                          'android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED'},
                         {item.get(ANDROID + 'name') for item in receivers[0].findall('intent-filter/action')})

    def test_restored_settings_only_offers_gemini_or_off_and_keeps_key_private(self):
        panel = ET.parse(ROOT / 'app/src/main/res/layout/dialog_ai_settings.xml').getroot()
        providers = panel.find(f".//RadioGroup[@{ANDROID}id='@+id/rg_agent_chat_provider']")
        self.assertIsNotNone(providers)
        self.assertEqual({'@+id/tab_provider_gemini', '@+id/tab_provider_off'},
                         {choice.get(ANDROID + 'id') for choice in providers})
        key = panel.find(f".//EditText[@{ANDROID}id='@+id/et_gemini_api_key']")
        self.assertIsNotNone(key)
        self.assertEqual('textPassword', key.get(ANDROID + 'inputType'))
        self.assertEqual('false', key.get(ANDROID + 'saveEnabled'))
        self.assertEqual('no', key.get(ANDROID + 'importantForAutofill'))

    def test_retired_features_are_not_advertised_as_provider_tools(self):
        with (ROOT / 'app/src/main/assets/ai/ai_assistant_guide.json').open(encoding='utf-8') as guide:
            tools = json.load(guide)['tools']
        names = [item['name'] for item in tools]
        self.assertEqual(len(set(names)), len(names))
        self.assertFalse(any(word in name for name in names for word in ('shell', 'termux', 'scriptlet', 'screenshot', 'vision', 'upload', 'raw_script')))

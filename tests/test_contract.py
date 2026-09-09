"""Check the browser-to-integration export boundary without a physical radio."""
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("profile_contract", ROOT / "custom_components/blueshark/profile.py")
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)

class ContractTest(unittest.TestCase):
    def test_browser_export_is_accepted_by_runtime(self):
        code = """
import {sampleSession,runtimeProfile} from './dist/core.mjs';
const s=sampleSession();s.synthetic=false;s.device.address='AA:BB:CC:DD:EE:FF';
s.commands=[{...s.observations[0],synthetic:false,id:'fixture',name:'Fixture only',stage:'tested',notes:'Automated contract fixture, not a physical-device test.'}];
console.log(JSON.stringify(runtimeProfile(s)));
"""
        result = subprocess.check_output(["node", "--input-type=module", "-e", code], cwd=ROOT, text=True)
        parsed = profile.parse_profile(result)
        self.assertEqual(parsed["commands"][0]["value"], "0101")
        self.assertIs(parsed["commands"][0]["response"], False)

if __name__ == '__main__':
    unittest.main()

# Validation record — developer preview

Automated in this workspace: 9 parser/profile-export safety tests initially; 7 Python profile validator tests; JavaScript syntax checks; Python compilation. Additional regression cases are maintained in `tests/`.

Interactive browser QA exercised capture import, session separation, exact byte retention, write/notification comparison, command hypothesis persistence, empty executable export for untested commands, blocked install export without an address, and guided capture content. A plain-HTTP UUID initialization bug was found and fixed.

Not validated here: physical BLE devices; Web Bluetooth radio operations; Android USB/Serial access; Home Assistant clean install, proxy/local adapter writes, HACS installation; Docker build/run and Supervisor ingress; GitHub Actions or GHCR publication. Compilation and mocked/unit checks do not replace those tests.

Any stable release must add a known device/firmware fixture and reproducible end-to-end tests. No current sample is a real-device support claim.

#!/usr/bin/env python3
"""Record live vendor/release evidence for the exact-version policy exception."""
import urllib.request
import xml.etree.ElementTree as ET

advisory = 'https://spring.io/security/cve-2026-47890/'
print('Vendor advisory (reviewed separately through web.run; urllib received HTTP 403):', advisory)
print('Recorded vendor assessment: SSE view-fragment rendering, severity LOW; scanner severity retained as CRITICAL.')
print('Recorded vendor fixes: 7.0.9 OSS; 6.2.20 Enterprise Support Only.')
metadata = 'https://repo.maven.apache.org/maven2/org/springframework/spring-webmvc/maven-metadata.xml'
with urllib.request.urlopen(metadata, timeout=30) as response:
    root = ET.fromstring(response.read())
versions = [row.text for row in root.findall('./versioning/versions/version')]
line = [version for version in versions if version.startswith('6.2.')]
print('Public release metadata:', metadata)
print('Public 6.2 releases:', ', '.join(line))
print('7.0.9 published:', '7.0.9' in versions)
assert line[-1] == '6.2.19' and '7.0.9' in versions, 'Reassess the exception: public fixed releases changed'

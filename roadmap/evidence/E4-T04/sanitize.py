#!/usr/bin/env python3
"""Redact generated credentials and hashes before committing verification output."""
import pathlib,re,sys

def sanitize(text):
    text=re.sub(r'eyJ[A-Za-z0-9_.-]+','eyJ...[truncated]',text)
    text=re.sub(r'(?i)(Bearer\s+)\S+',r'\1[truncated]',text)
    text=re.sub(r'(?i)((?:access_token|refresh_token|id_token|client_secret|password|X-Api-Key|signature)["\s:=]+)[^\s,}&"]+',r'\1[truncated]',text)
    text=re.sub(r'\b[0-9a-fA-F]{24,}\b','[hash truncated]',text)
    return text

if __name__=='__main__':
    source,target=map(pathlib.Path,sys.argv[1:]);target.write_text(sanitize(source.read_text()))

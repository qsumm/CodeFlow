#!/usr/bin/env python3
"""Create a bucket in MinIO/S3 via SigV4 signed CreateBucket PUT, stdlib only."""
import hashlib, hmac, os, sys, urllib.request, datetime, xml.etree.ElementTree as ET

ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localhost:9000")
AK, SK = os.environ.get("S3_AK", "minioadmin"), os.environ.get("S3_SK", "minioadmin")
REGION = "us-east-1"
BUCKET = sys.argv[1] if len(sys.argv) > 1 else "ainative"

def sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()

host = ENDPOINT.split("//", 1)[1]
path = f"/{BUCKET}"
amz_date = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
date_stamp = amz_date[:8]
payload_hash = hashlib.sha256(b"").hexdigest()
canonical_headers = f"host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
signed_headers = "host;x-amz-content-sha256;x-amz-date"
canonical_request = f"PUT\n{path}\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}"
scope = f"{date_stamp}/{REGION}/s3/aws4_request"
string_to_sign = f"AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{hashlib.sha256(canonical_request.encode()).hexdigest()}"
k = sign(sign(sign(sign(("AWS4" + SK).encode(), date_stamp), REGION), "s3"), "aws4_request")
signature = hmac.new(k, string_to_sign.encode(), hashlib.sha256).hexdigest()
auth = f"AWS4-HMAC-SHA256 Credential={AK}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"

req = urllib.request.Request(
    f"{ENDPOINT}{path}", method="PUT",
    headers={"Authorization": auth, "x-amz-content-sha256": payload_hash,
             "x-amz-date": amz_date, "Content-Length": "0"})
try:
    with urllib.request.urlopen(req, timeout=15) as resp:
        print(f"bucket {BUCKET}: created ({resp.status})")
except urllib.error.HTTPError as e:
    body = e.read().decode(errors="replace")
    if "BucketAlreadyOwnedByYou" in body or "BucketAlreadyExists" in body:
        print(f"bucket {BUCKET}: already exists")
    else:
        print(f"bucket {BUCKET}: HTTP {e.code}\n{body}")
        sys.exit(1)

# list buckets to verify (best-effort: 签名按单请求计算,列出失败不影响创建结论)
try:
    amz_date2 = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    payload_hash2 = hashlib.sha256(b"").hexdigest()
    cr2 = f"GET\n/\n\nhost:{host}\nx-amz-content-sha256:{payload_hash2}\nx-amz-date:{amz_date2}\n"
    sts2 = f"AWS4-HMAC-SHA256\n{amz_date2}\n{scope}\n{hashlib.sha256(cr2.encode()).hexdigest()}"
    sig2 = hmac.new(k, sts2.encode(), hashlib.sha256).hexdigest()
    auth2 = f"AWS4-HMAC-SHA256 Credential={AK}/{scope}, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature={sig2}"
    req2 = urllib.request.Request(f"{ENDPOINT}/", headers={
        "Authorization": auth2, "x-amz-content-sha256": payload_hash2, "x-amz-date": amz_date2})
    with urllib.request.urlopen(req2, timeout=15) as resp2:
        root = ET.fromstring(resp2.read())
    print("buckets now:", [b.findtext("{*}Name") for b in root.iter("{*}Bucket")])
except Exception as e:  # noqa: BLE001
    print(f"(bucket list verify skipped: {e})")

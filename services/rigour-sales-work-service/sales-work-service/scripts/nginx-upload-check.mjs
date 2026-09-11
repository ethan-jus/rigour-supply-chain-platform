#!/usr/bin/env node
// Validate the repository Nginx template with synthetic streamed bodies only; no production requests.
import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const image = process.env.NGINX_TEST_IMAGE || 'nginx:1.18-alpine@sha256:93baf2ec1bfefd04d29eb070900dd5d79b0f79863653453397e55a5b663a6cb1';
const id = randomUUID();
const name = `checkin-nginx-upload-${id}`;
const directory = await mkdtemp(join(tmpdir(), 'checkin-nginx-upload-'));
const MiB = 1024 * 1024;
const calls = [];
const upstream = createServer((incoming, outgoing) => {
    if (new URL(incoming.url, 'http://fixture').searchParams.get('fixture') !== id) {
        outgoing.writeHead(404); outgoing.end(); return;
    }
    let bytes = 0;
    incoming.on('data', chunk => { bytes += chunk.length; });
    incoming.on('end', () => {
        calls.push({ path: incoming.url.split('?')[0], bytes });
        outgoing.writeHead(200, { 'Content-Type': 'application/json' });
        outgoing.end(JSON.stringify({ bytes }));
    });
});
await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
let running = false;

function sendBody(port, path, fileBytes, multipart = true) {
    const boundary = 'checkin-synthetic-upload';
    const prefix = multipart ? Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.wav"\r\nContent-Type: audio/wav\r\n\r\n`) : Buffer.alloc(0);
    const suffix = multipart ? Buffer.from(`\r\n--${boundary}--\r\n`) : Buffer.alloc(0);
    const total = prefix.length + fileBytes + suffix.length;
    return new Promise((resolve, reject) => {
        let sent = 0, responseArrived = false;
        const outgoing = request({ hostname: '127.0.0.1', port, path: `${path}?fixture=${id}`, method: 'POST',
            headers: { 'Content-Length': total, 'Content-Type': multipart ? `multipart/form-data; boundary=${boundary}` : 'application/octet-stream', Expect: '100-continue' } });
        outgoing.on('continue', () => {
            const data = Readable.from((async function* () {
                if (prefix.length) { sent += prefix.length; yield prefix; }
                const chunk = Buffer.alloc(64 * 1024);
                for (let remaining = fileBytes; remaining > 0;) {
                    const size = Math.min(remaining, chunk.length); remaining -= size; sent += size;
                    yield chunk.subarray(0, size);
                }
                if (suffix.length) { sent += suffix.length; yield suffix; }
            })());
            void pipeline(data, outgoing).catch(error => { if (!responseArrived) reject(error); });
        });
        outgoing.on('response', incoming => {
            responseArrived = true;
            let text = '';
            incoming.setEncoding('utf8');
            incoming.on('data', chunk => { text += chunk; });
            incoming.on('end', () => resolve({ status: incoming.statusCode, sent, expected: total, text }));
        });
        outgoing.on('error', error => { if (!responseArrived) reject(error); });
        outgoing.setTimeout(120000, () => outgoing.destroy(new Error('Local proxy test timed out')));
        outgoing.flushHeaders();
    });
}

try {
    const original = await readFile(join(root, 'deploy/nginx/sales-checkin-locations.conf'), 'utf8');
    assert.equal((original.match(/client_max_body_size 270m;/g) || []).length, 1);
    assert.ok(original.includes('proxy_request_buffering off;'));
    const locations = original.replaceAll('http://127.0.0.1:26886', `http://host.docker.internal:${upstream.address().port}`);
    await mkdir(join(directory, 'snippets'));
    await writeFile(join(directory, 'snippets/sales-checkin-proxy-marker.conf'), 'proxy_set_header X-Sales-Checkin-Proxy-Marker local-fixture;\n');
    await writeFile(join(directory, 'locations.conf'), locations);
    await writeFile(join(directory, 'limits.conf'), await readFile(join(root, 'deploy/nginx/sales-checkin-limits.conf')));
    await writeFile(join(directory, 'nginx.conf'), `worker_processes 1;\nerror_log /dev/stderr warn;\nevents { worker_connections 128; }\nhttp { access_log off; include /fixture/limits.conf; server { listen 8080; server_name localhost; include /fixture/locations.conf; } }\n`);
    const mounts = ['-v', `${directory}:/fixture:ro`, '-v', `${join(directory, 'snippets')}:/etc/nginx/snippets:ro`, '-v', `${join(directory, 'nginx.conf')}:/etc/nginx/nginx.conf:ro`];
    const syntax = await run('docker', ['run', '--rm', ...mounts, image, 'nginx', '-t'], { timeout: 30000 });
    assert.ok(syntax.stderr.includes('test is successful'));
    console.log('PASS nginx 1.18 template syntax');
    await run('docker', ['run', '--rm', '--name', name, '-d', '-p', '127.0.0.1::8080', ...mounts, image], { timeout: 30000 });
    running = true;
    const port = Number((await run('docker', ['inspect', '--format', '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}', name])).stdout.trim());
    const mediaPath = '/sales-checkin/api/v1/submissions/00000000-0000-4000-8000-000000000001/media/audio/00000000-0000-4000-8000-000000000002';
    for (const megabytes of [1, 190, 256]) {
        const result = await sendBody(port, mediaPath, megabytes * MiB);
        assert.equal(result.status, 200, `${megabytes} MiB file must reach synthetic upstream`);
        assert.equal(JSON.parse(result.text).bytes, result.expected, 'all body bytes reach upstream');
        assert.equal(result.sent, result.expected);
        console.log(`PASS ${megabytes} MiB multipart body forwarded (${result.expected} bytes)`);
    }
    for (const [path, limit] of [[mediaPath, 270 * MiB], ['/sales-checkin/admin/api/v1/submissions', 30 * MiB],
        ['/sales-checkin/api/v1/locations/resolve', 64 * 1024], ['/sales-checkin/api/v1/identity/verify', 64 * 1024]]) {
        const before = calls.length;
        const result = await sendBody(port, path, limit + 1, false);
        assert.equal(result.status, 413, 'oversized request rejected by its own location limit');
        assert.equal(result.sent, 0, 'known oversized body rejected before streaming');
        assert.equal(calls.length, before, 'rejected request must not reach upstream');
        console.log(`PASS ${path} limit ${limit} bytes retained; +1 rejected with 413`);
    }
    console.log('PASS 7 real proxy body checks; synthetic data only, no application or object storage writes');
} finally {
    if (running) await run('docker', ['rm', '-f', name]).catch(() => {});
    upstream.closeAllConnections();
    await new Promise(resolve => upstream.close(resolve));
    await rm(directory, { recursive: true, force: true });
}

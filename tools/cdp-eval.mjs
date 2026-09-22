// Minimal CDP client: evaluates an expression in the app WebView and prints the result.
// Requires the devtools port to be forwarded first:
//   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
// Usage: node cdp-eval.mjs "<expression>"
const expression = process.argv[2] ?? '1+1';

const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = targets.find((t) => t.type === 'page');
if (!page) throw new Error('no page target');

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();

function send(method, params) {
    const messageId = ++id;
    ws.send(JSON.stringify({ id: messageId, method, params }));
    return new Promise((resolve) => pending.set(messageId, resolve));
}

ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
        pending.get(message.id)(message);
        pending.delete(message.id);
    }
});

await new Promise((resolve) => ws.addEventListener('open', resolve));

const result = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true
});

console.log(JSON.stringify(result.result, null, 2));
ws.close();

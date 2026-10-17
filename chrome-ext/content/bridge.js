/* Content-script side of the worker connection: one long-lived port, cancellable requests. */
(function () {
  'use strict';
  const Q = self.Quill;

  let port = null;
  let seq = 0;
  const pending = new Map();

  const failAll = (error) => {
    pending.forEach((p) => p.reject(error));
    pending.clear();
  };

  function connect() {
    port = chrome.runtime.connect({ name: 'quill' });
    port.onMessage.addListener((m) => {
      const p = pending.get(m.id);
      if (!p) return;
      if (m.delta != null) {
        if (p.onDelta) p.onDelta(m.delta);
        return;
      }
      pending.delete(m.id);
      if (m.error) p.reject(m.error);
      else p.resolve(m);
    });
    port.onDisconnect.addListener(() => {
      port = null;
      failAll({ code: 'disconnected', message: 'Lost the connection to Quill. Reload the page if this keeps happening.' });
    });
  }

  /** True once the extension was reloaded or removed underneath this page. */
  Q.orphaned = () => {
    try {
      return !chrome.runtime || !chrome.runtime.id;
    } catch (e) {
      return true;
    }
  };

  /**
   * Sends one model request. Returns { promise, cancel }.
   * job: { kind: 'fix'|'style'|'rewrite'|'explain', system, user, format?, stream?, prio? }
   */
  Q.ask = function (job, onDelta) {
    const id = ++seq;
    const promise = new Promise((resolve, reject) => {
      if (Q.orphaned()) return reject({ code: 'orphaned', message: 'Quill was updated. Reload this page to keep using it.' });
      try {
        if (!port) connect();
        pending.set(id, { resolve, reject, onDelta });
        port.postMessage(Object.assign({ id }, job));
      } catch (e) {
        pending.delete(id);
        port = null;
        reject({ code: 'disconnected', message: 'Quill was updated. Reload this page to keep using it.' });
      }
    });
    const cancel = () => {
      const p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      try {
        port.postMessage({ id, cancel: true });
      } catch (e) {}
      p.reject({ code: 'aborted', message: 'Cancelled' });
    };
    return { promise, cancel };
  };
})();

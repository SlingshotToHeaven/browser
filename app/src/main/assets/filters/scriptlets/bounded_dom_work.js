// Bundled support for the cosmetic and popup scriptlets. Passed in a private lexical scope.
(function(root, attributeFilter, acceptAttribute, consume) {
  var SLICE = 64, INITIAL = 4096, SUBTREE = 512, QUEUE = 32, DELIVERY = 64;
  var jobs = [], queued = new Map(), waiting = false;

  function work(node, limit) {
    return { root: node, node: node, left: limit, walk: null };
  }
  var initial = root ? work(root, INITIAL) : null;

  function schedule() {
    if (waiting || (!initial && !jobs.length)) return;
    waiting = true;
    if (window.requestAnimationFrame) window.requestAnimationFrame(drain);
    else window.setTimeout(drain, 32);
  }

  function drain() {
    waiting = false;
    var batch = new Set();
    for (var i = 0; i < SLICE && (initial || jobs.length); i++) {
      // Mutation traffic cannot starve the one initial walk; neither path restarts it.
      var first = initial && (i % 2 === 0 || !jobs.length);
      var job = first ? initial : jobs[0];
      var node = job.node;
      if (job.root.isConnected && job.root.ownerDocument === document) {
        if (node.isConnected && node.ownerDocument === document) batch.add(node);
        if (--job.left > 0) {
          if (!job.walk) job.walk = document.createTreeWalker(job.root, 1);
          job.node = job.walk.nextNode();
          if (job.node) continue;
        }
      }
      if (first) initial = null;
      else { jobs.shift(); queued.delete(job.root); }
    }
    try { if (batch.size) consume(batch); }
    finally { schedule(); }
  }

  function add(node, limit) {
    if (node.nodeType !== 1 || !node.isConnected) return;
    var existing = queued.get(node);
    if (existing) {
      // A coalesced attribute record must not mask traversal of a newly added subtree.
      if (!existing.walk && existing.left < limit) existing.left = limit;
      return;
    }
    var job = work(node, limit);
    queued.set(node, job);
    jobs.push(job);
  }

  if (window.MutationObserver) {
    var observer = new window.MutationObserver(function(records) {
      var slots = DELIVERY;
      // Never query the mutation target's whole subtree or fall back to a document scan.
      for (var i = records.length - 1; i >= Math.max(0, records.length - DELIVERY) && slots && jobs.length < QUEUE; i--) {
        var record = records[i];
        if (record.type === 'attributes') {
          if (acceptAttribute(record.target)) add(record.target, 1);
        } else {
          for (var j = record.addedNodes.length - 1; j >= 0 && slots && jobs.length < QUEUE; j--, slots--) {
            add(record.addedNodes[j], SUBTREE);
          }
        }
      }
      schedule();
    });
    observer.observe(document, {subtree: true, childList: true, attributes: true, attributeFilter: attributeFilter});
  }
  schedule();
})

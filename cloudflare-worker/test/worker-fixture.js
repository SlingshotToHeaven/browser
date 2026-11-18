import worker, { RuleStateCatalog as Catalog } from '../worker.js';

// Test-only failure after the catalog row and the first chunk have been staged.
export class RuleStateCatalog extends Catalog {
  constructor(state) {
    super(state);
    this.sql.exec(`CREATE TRIGGER IF NOT EXISTS test_fail_second_chunk
      BEFORE INSERT ON state_chunks WHEN NEW.part = 1 AND
      (SELECT description FROM states WHERE seq = NEW.seq) = 'inject commit failure'
      BEGIN SELECT RAISE(ABORT, 'injected write failure'); END`);
  }
}

export default worker;

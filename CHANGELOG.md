## Version 0.1.1

### API Changes

- Text collation is now en_US.utf8; this is *also* almost certainly broken, but
  should hopefully be more portable.

### New Features

- `workload.default-value`: adds columns with a default value and verifies that
  inserted rows have those defaults.
- `jepsen.sql/opt-fn`: infer --expected-consistency-model from --isolation.


### Dependencies

- Clojure 1.12.6
- Jepsen 0.3.14
- next.jdbc 1.3.1118
- postgresql 42.7.13

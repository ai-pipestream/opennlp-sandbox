# Goals: Workspace search tab

## P1

- [ ] Tab label "Live index search"; "workspace" and "dynamic" out of every string; heading "Search the
      documents you analyze"; new bridge paragraph with jumps to Lifecycle and
      Corpus search (`findings/what-is-a-workspace.md` section 5).
- [ ] "What is a live index?" `details.help-callout` above the existing how-to
      (text from `findings/what-is-a-workspace.md` section 5.2 with the noun swapped).
- [ ] First-run empty state with a primary jump to Analyze ("Analyze a document
      and add it to a workspace") and a secondary jump to Workflows.
- [ ] Rename "Clear workspace index" to "Delete this live index" and confirm before
      deleting; test the delete path.
- [ ] Let the user name a live index; default to the document title instead of
      the constant "Workbench index" (`semantic-workbench.ts:331`).
- [ ] Give `LifecycleWorkbench` an `onIndexChanged` and clear `#workspace` when
      the picker is rebuilt.
- [ ] Search capability block on `service-info` (dynamic indexing enabled,
      providers, persistence configured); brown out the tab on 501 with the reason.
- [ ] Tests: delete path, Add button, result list, error paths, `#index-count`,
      provider lock; update `test/index.test.ts:105` for the heading.

## P2

- [ ] "In memory" / "Saved to disk" / "Read-only" chip in both pickers; map `persisted` in
      `search-adapter.ts`.
- [ ] Rename "Semantic query" to "What are you looking for?"; "Attached to"
      to "Searching"; "Detached." to "Nothing selected."; storage row shows the
      live state instead of the constant "gRPC server memory".
- [ ] Jumps to Models & data (no embedding model) and Trainer (distilled model).
- [ ] Say next to the storage choice that exact storage cannot be saved, until
      `flat_float` becomes persistable.

## P3

- [ ] Align the section id `session-search` with the label, updating the e2e spec.

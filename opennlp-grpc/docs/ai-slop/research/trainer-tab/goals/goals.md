# Goals: Trainer tab

## P1

- [ ] Split the disabled-training message into "no artifact root" and "no
      teacher installed"; the latter links to Models & data (`data-workbench-jump="models"`)
      and disables the Distill button instead of scolding after the click.
- [ ] Say in the help callout that a distilled model serves immediately under its
      artifact id, with no restart.
- [ ] Add `license`, `languages`, `teacher_reference`, `teacher_revision` to
      `StaticModelDescriptor` and fill them from the teacher's catalog entry at
      distillation time.
- [ ] Label the `family` value as "Tokenizer: WordPiece"; fallback "tokenizer family unknown".
- [ ] Tests: no-teacher branch, dictionary import through the UI, delete through
      the UI, `onModelsChanged` populating the Analyze embedding selector.

## P2

- [ ] Rename: "Train model" to "Distill model"; "3 · Train a static model" to
      "3 · Distill a static embedding model"; "static model" to "static embedding model"
      everywhere; "Download TSV" to "Export vocabulary TSV"; "Min frequency" to
      "Min term frequency (total occurrences)".
- [ ] Help callout: one sentence that distillation is not gradient training
      (no epochs, no loss), shared with the Workflows tab's "What 'train' means here".
- [ ] Define the three vocabularies (teacher subword, learned corpus, embedding
      table) in the help callout.
- [ ] Vocabulary coverage flyout text (`findings/terminology.md` section 10) and the
      threshold label "Report drift after this many out-of-vocabulary terms".
- [ ] Emit `model-card.json` beside `manifest.tsv` with the fields in
      `findings/artifact-and-export.md` section 5; add an import path for a bucket
      prefix that carries one.
- [ ] Tags on each trained model row: capability, runtime, distilled-from, tokenizer,
      dimension, vocabulary, language, license.
- [ ] Jumps from the success message to Analyze and Workspace search; a jump
      from Lifecycle's drift panel back to step 2 here.
- [ ] Progress: elapsed time and a cancel affordance during distillation;
      replace the generic "TrainStaticModel failed" with the server message.
- [ ] Reload dictionaries and vocabularies on refresh (`/api/v1/dictionaries`
      exists; a `ListVocabularies` RPC does not).
- [ ] Tests: TSV save path, `copyText` failure, `boundedInt` and `asRatio` edges,
      the busy guard.

## P3

- [ ] Content-addressed artifact ids, or record input hashes so two distillations
      of the same inputs are recognisably the same model.
- [ ] Share the three-step implementation with the Workflows tab instead of
      duplicating it.

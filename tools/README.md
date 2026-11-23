# tools

Scripts run by hand on a development machine (Python 3, no dependency). Nothing here is part of the build.

## The sections of `vectors/sealed.json` added by Android

`vectors/sealed.json` comes from collective-belfry (`services/onboarding/tools/sealed_vectors.py`). Five
sections were added here, each by its own generator, in this order in the file:

1. `confirm`: `sealed_confirm_vectors.py` (also holds the small pure-Python crypto the others import)
2. `notes`: `sealed_notes_vectors.py`
3. `silent`: `sealed_silent_vectors.py`
4. `join`: `sealed_join_vectors.py`
5. `v2`: `sealed_v2_vectors.py` (docs/sealed-v2.md; it reads the test keys of `follow` and `join`)

Each generator rewrites only its own section and leaves every other byte of the file as it was.

- Check that a section is what its generator makes (changes nothing):
  `python3 tools/sealed_join_vectors.py vectors/sealed.json --check`
  (give the path: `sealed_confirm_vectors.py` reads its first argument as the file).
- Write a section again: the same without `--check`. A generator puts its section LAST in the file, so
  only the generator of the last section (`v2`) can be run on the file as it stands. To change an
  earlier section, remove the sections after it, run its generator, then run the later ones again in
  the order above, and check that the part of the file before the changed section is byte for byte
  what it was.

`adts2m4a.py`: the reference for `core/audio/M4a.kt` (see CLAUDE.md, Meetings).

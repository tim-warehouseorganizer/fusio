# csv-spectrum upstream PR — ready to submit

A fix for a ground-truth mismatch in maxogden/csv-spectrum, found while
vendoring the corpus into fusio's compat suite.

**The bug:** `csvs/location_coordinates.csv` has phone number `2095257564`,
but `json/location_coordinates.json` says `1234567890` — the JSON was
anonymized at some point and the CSV wasn't, so any parser validated against
the corpus fails this file through no fault of its own.

**The fix** (one line, prepared as a commit on branch
`fix-location-coordinates-phone-mismatch` in the local clone at the session
scratchpad — or just recreate it, it's a one-word change): align the JSON
with the CSV (`1234567890` → `2095257564`). Aligning in this direction keeps
the CSV bytes stable for existing consumers; if the project prefers the
anonymized value, the equivalent fix is editing the CSV instead.

**To submit** (needs your GitHub account):
```
gh repo fork maxogden/csv-spectrum --clone csv-spectrum-fork
cd csv-spectrum-fork
# apply the one-line change to json/location_coordinates.json
git checkout -b fix-location-coordinates-phone-mismatch
git commit -am "Fix phone number mismatch between location_coordinates csv and json"
git push -u origin HEAD
gh pr create --title "Fix phone number mismatch in location_coordinates ground truth" --body-file pr-body.md
```

**Suggested PR body:**

> `json/location_coordinates.json` lists `"Contact Phone Number": "1234567890"`
> while `csvs/location_coordinates.csv` contains `2095257564` for the same
> record — it looks like the phone number was anonymized in the JSON ground
> truth but not in the CSV, so parsers validated against the corpus fail this
> file even when they parse it correctly.
>
> This PR aligns the JSON with the CSV's actual value. If you'd rather keep
> the anonymized number, happy to flip the change to edit the CSV instead —
> either way the pair becomes self-consistent.
>
> Found while adding csv-spectrum to the test suite of a CSV parser (thanks
> for maintaining this corpus — it caught a real bug in our parser too).

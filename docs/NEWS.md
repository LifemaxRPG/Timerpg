# Town newspapers and radio

Import the JSON API from `src/news`.

| Call | Example |
|---|---|
| `paperPrice(1952)` | `5` nominal cents |
| `edition({name:"Capital"}, day, events, 1952, "town")` | Returns the Capital Courier with name, day, year, cover price, and numbered stories |
| `radioAt(1952, 6.2, 2, "capital")` | Returns a stable station call sign, `"farm_report"` and a null genre |
| `tvNewsAvailable(1948)` | `true`, an eligibility flag for future TV programming |

Resolve `day` with the calendar's `dayNumberOf`. The issue day must be in the supplied year. A town can supply an optional `paperName`. A reported crime can be supplied like this, using the issue's absolute day:

```js
{
  id: "hardware_break_in",
  kind: "crime",
  day: day - 1,
  reportedDay: day,
  subject: "Thief Takes Tools from Elm St. Hardware",
  detail: "Police took a report from the hardware store.",
  publicDescription: "Man in Grey Coat"
}
```

Only public text belongs in these fields. An unpublished crime uses a null report day. The crime appears on its report day and duplicate IDs within the issue are collapsed. Noncrime events use the same common fields and publish on their event day. Kinds are fire, death, opening, closing, weather and prices. Set the optional `major` flag for page one. Otherwise local headlines go on page three and weather, prices and holidays go on page two. Community notices, a seeded recreation score and an ad provide background copy. The score and ad are decorative and carry no standings or inventory changes.

Radio accepts decimal local hours and Sunday-based weekday numbers 0–6. Its first five minutes each hour carry news during broadcast hours. In 1952 the farm report follows at 06:05 and music follows the 07:00 news. Older stations go off air from 23:00 to 06:00. The module returns programme data and genre names. The game handles playback and spoken copy.

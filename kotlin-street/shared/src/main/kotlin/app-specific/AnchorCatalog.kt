package com.lifemax.rpg.feature.habits.domain

/** The part of the day an anchor belongs to: what is offered first follows the clock. */
enum class DayPart {
    MORNING,
    DAY,
    EVENING,
    NIGHT,
}

/** What kind of moment an anchor is, so the habits that fit it can be offered first. */
enum class Spot {
    WAKE,
    WASH,
    MEAL,
    COMMUTE,
    WORK,
    BREAK,
    HOME,
    EVENING,
    BED,
    EXERCISE,
    PETS,
    KIDS,
    WEEKEND,
}

/**
 * Something most people already do every day, said the way a foundation is: [cue]. [keys] are other
 * words it is found by, in lower case.
 */
data class AnchorIdea(val cue: String, val part: DayPart, val spot: Spot, val keys: List<String>)

/**
 * The foundations offered while one is typed. Plain data, in the order they are offered within a
 * part of the day: add a line to add an anchor. A cue is at most [HabitsRules.LIMITS]' `cue` long.
 */
object AnchorCatalog {
    private fun a(cue: String, part: DayPart, spot: Spot, vararg keys: String) =
        AnchorIdea(cue, part, spot, keys.toList())

    private val M = DayPart.MORNING
    private val D = DayPart.DAY
    private val E = DayPart.EVENING
    private val N = DayPart.NIGHT

    val ALL: List<AnchorIdea> =
        listOf(
            // waking up
            a("After waking up", M, Spot.WAKE, "wake", "woke", "get up", "morning", "alarm"),
            a("After coffee", M, Spot.MEAL, "morning coffee", "espresso", "caffeine", "cup"),
            a("After brushing my teeth", M, Spot.WASH, "brush", "tooth", "toothbrush"),
            a("After my shower", M, Spot.WASH, "showering", "wash", "bath"),
            a("After breakfast", M, Spot.MEAL, "eat", "eating", "cereal", "morning meal"),
            a("After making my bed", M, Spot.WAKE, "make the bed", "bedroom"),
            a("After my alarm goes off", M, Spot.WAKE, "wake", "clock", "ring"),
            a("After getting out of bed", M, Spot.WAKE, "wake", "get up", "rise"),
            a("Before my shower", M, Spot.WASH, "showering", "wash", "bath"),
            a("Before breakfast", M, Spot.MEAL, "eat", "eating", "morning meal"),
            a("Before coffee", M, Spot.MEAL, "morning coffee", "caffeine"),
            a("While my coffee brews", M, Spot.MEAL, "kettle", "brewing", "wait", "pot"),
            a("After my morning tea", M, Spot.MEAL, "cuppa", "kettle"),
            a("After getting dressed", M, Spot.WAKE, "clothes", "dress", "outfit"),
            a("After washing my face", M, Spot.WASH, "face wash", "sink", "skincare"),
            a("After shaving", M, Spot.WASH, "shave", "razor", "beard"),
            a("After doing my hair", M, Spot.WASH, "comb", "brush hair", "style"),
            a("After using the toilet", M, Spot.WASH, "bathroom", "restroom", "loo"),
            a("After opening the curtains", M, Spot.WAKE, "blinds", "window", "daylight"),
            a("After my morning walk", M, Spot.EXERCISE, "stroll", "walking"),
            a("After taking my medicine", M, Spot.WASH, "meds", "pills", "medication", "tablets"),
            a("After feeding the cat", M, Spot.PETS, "pet", "kitten", "feed"),
            a("After feeding the dog", M, Spot.PETS, "pet", "puppy", "feed"),
            a("After walking the dog", M, Spot.PETS, "pet", "puppy", "walk"),
            a("After the school run", M, Spot.KIDS, "drop off", "children", "kids"),
            a("After the kids leave", M, Spot.KIDS, "children", "school", "bus"),
            a("After packing lunches", M, Spot.KIDS, "lunch box", "children", "kids"),
            // getting there
            a("Before leaving for work", M, Spot.COMMUTE, "leave", "go to work", "front door"),
            a("Before leaving the house", M, Spot.COMMUTE, "leave", "front door", "go out"),
            a("After locking the front door", M, Spot.COMMUTE, "leave", "keys", "lock"),
            a("After starting the car", M, Spot.COMMUTE, "drive", "driving", "engine", "commute"),
            a("After parking the car", M, Spot.COMMUTE, "park", "drive", "driving", "commute"),
            a("On the bus", M, Spot.COMMUTE, "commute", "ride", "transit"),
            a("On the train", M, Spot.COMMUTE, "commute", "ride", "subway", "metro", "transit"),
            a("On my commute", M, Spot.COMMUTE, "bus", "train", "drive", "travel", "ride"),
            a("After getting to work", M, Spot.WORK, "arrive", "office", "job"),
            a("After dropping my bag", M, Spot.WORK, "arrive", "backpack", "desk"),
            // work and school
            a("When I sit down at my desk", D, Spot.WORK, "office", "work", "computer", "chair"),
            a("After opening my laptop", D, Spot.WORK, "computer", "work", "log in", "start work"),
            a("After checking my email", D, Spot.WORK, "inbox", "mail", "messages"),
            a("Before my first meeting", D, Spot.WORK, "call", "stand-up", "work"),
            a("After a meeting", D, Spot.WORK, "call", "work"),
            a("After hanging up a call", D, Spot.WORK, "phone", "meeting"),
            a("After getting to school", D, Spot.WORK, "class", "campus", "college", "arrive"),
            a("Before class", D, Spot.WORK, "school", "lecture", "college", "lesson"),
            a("After class", D, Spot.WORK, "school", "lecture", "college", "lesson"),
            a("Before I start studying", D, Spot.WORK, "study", "homework", "revision"),
            a("After I finish studying", D, Spot.WORK, "study", "homework", "revision"),
            a("After lunch", D, Spot.MEAL, "noon", "midday", "eat", "eating"),
            a("On my lunch break", D, Spot.BREAK, "noon", "midday", "work", "eat"),
            a("After work", D, Spot.HOME, "finish", "job", "office", "clock out"),
            a("After refilling my water", D, Spot.BREAK, "bottle", "drink", "tap"),
            a("On my coffee break", D, Spot.BREAK, "pause", "rest", "tea break"),
            a("On my break", D, Spot.BREAK, "pause", "rest", "shift", "work"),
            a("Before lunch", D, Spot.MEAL, "noon", "midday", "eat", "eating"),
            a("After my afternoon coffee", D, Spot.BREAK, "tea", "cup", "caffeine"),
            a("After closing my laptop", D, Spot.WORK, "computer", "log off", "finish work"),
            a("Before leaving work", D, Spot.WORK, "finish", "clock out", "office", "go home"),
            a("After school", D, Spot.HOME, "class", "college", "finish"),
            a("After school pickup", D, Spot.KIDS, "children", "kids", "collect"),
            // bedtime
            a("Before bed", N, Spot.BED, "sleep", "night", "bedtime"),
            a("At lights out", N, Spot.BED, "sleep", "night", "bedtime", "lamp", "dark"),
            a("After putting on pyjamas", N, Spot.BED, "pajamas", "nightwear", "change", "pjs"),
            a("After brushing at night", N, Spot.BED, "teeth", "tooth", "toothbrush", "floss"),
            a("After plugging in my phone", N, Spot.BED, "charge", "charger", "charging"),
            a("After setting my alarm", N, Spot.BED, "clock", "tomorrow", "wake"),
            a("After getting into bed", N, Spot.BED, "sleep", "night", "bedtime", "lie down"),
            // shift work
            a("Before my shift", N, Spot.WORK, "work", "night shift", "clock in", "overnight"),
            a("After clocking in", N, Spot.WORK, "shift", "work", "punch in", "start work"),
            a("At the start of my shift", N, Spot.WORK, "work", "night shift", "overnight"),
            a("On my meal break", N, Spot.BREAK, "shift", "lunch", "eat", "work"),
            a("Halfway through my shift", N, Spot.BREAK, "work", "night shift", "middle", "midway"),
            a("After my rounds", N, Spot.WORK, "shift", "patrol", "check", "work"),
            a("After handover", N, Spot.WORK, "shift", "hand over", "report", "work"),
            a("After my shift", N, Spot.HOME, "work", "night shift", "finish", "overnight"),
            a("After clocking out", N, Spot.HOME, "shift", "work", "punch out", "finish"),
            a("When I'm home from my shift", N, Spot.HOME, "work", "night shift", "get home"),
            a("Before my daytime sleep", N, Spot.BED, "shift", "nap", "bed", "blackout"),
            a("After waking for my shift", N, Spot.WAKE, "work", "night shift", "wake", "get up"),
            // home again
            a("After getting home", E, Spot.HOME, "arrive", "come home", "front door", "house"),
            a("After taking my shoes off", E, Spot.HOME, "get home", "boots", "front door"),
            a("After changing my clothes", E, Spot.HOME, "get home", "change", "dress"),
            a("After hanging up my keys", E, Spot.HOME, "get home", "front door", "hook"),
            a("After checking the mail", E, Spot.HOME, "post", "letters", "mailbox"),
            a("After a workout", E, Spot.EXERCISE, "gym", "exercise", "training", "run"),
            a("Before a workout", E, Spot.EXERCISE, "gym", "exercise", "training", "run"),
            a("After my run", E, Spot.EXERCISE, "jog", "running", "exercise"),
            a("After the gym", E, Spot.EXERCISE, "workout", "exercise", "training", "lift"),
            a("After my evening walk", E, Spot.EXERCISE, "stroll", "walking"),
            a("After helping with homework", E, Spot.KIDS, "children", "kids", "school"),
            // evening
            a("After dinner", E, Spot.MEAL, "supper", "eat", "eating", "evening meal"),
            a("Before dinner", E, Spot.MEAL, "supper", "eat", "eating", "evening meal", "cook"),
            a("While dinner cooks", E, Spot.MEAL, "supper", "cooking", "oven", "wait", "kitchen"),
            a(
                "After doing the dishes",
                E,
                Spot.EVENING,
                "wash up",
                "dishwasher",
                "kitchen",
                "sink",
            ),
            a("After clearing the table", E, Spot.EVENING, "dinner", "kitchen", "tidy"),
            a("After taking the bins out", E, Spot.EVENING, "trash", "rubbish", "garbage"),
            a("After the kids' bath", E, Spot.KIDS, "children", "bath time", "baby"),
            a("After the kids' bedtime", E, Spot.KIDS, "children", "put to bed", "baby", "asleep"),
            a("After the bedtime story", E, Spot.KIDS, "children", "kids", "read", "book"),
            a("After turning off the TV", E, Spot.EVENING, "television", "show", "episode"),
            a("Before I turn on the TV", E, Spot.EVENING, "television", "show", "episode"),
            a("After my evening tea", E, Spot.EVENING, "cuppa", "kettle", "herbal"),
            a("After my evening shower", E, Spot.WASH, "showering", "wash", "bath"),
            a("After my bath", E, Spot.WASH, "bathe", "tub", "soak", "wash"),
            // weekends
            a("On Saturday morning", M, Spot.WEEKEND, "weekend", "day off"),
            a("On Sunday morning", M, Spot.WEEKEND, "weekend", "day off"),
            a("On Sunday evening", E, Spot.WEEKEND, "weekend", "week ahead", "night"),
            a("After the weekly shop", D, Spot.WEEKEND, "groceries", "shopping", "supermarket"),
            a("After doing the laundry", D, Spot.WEEKEND, "washing", "clothes", "chores"),
            a("After the weekend lie-in", M, Spot.WEEKEND, "sleep in", "day off", "wake"),
        )
}

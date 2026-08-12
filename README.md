DenizenCore
===========

The core Denizen engine

To be implemented and extended by separate DenizenScript projects.

Please posts issues to the [Denizen Repo](https://github.com/DenizenScript/Denizen/issues).

For API usage, refer to the [Denizen README](https://github.com/DenizenScript/Denizen).

If you are implementing your own version of Denizen using this core, this topic is not yet fully documented, but talk to us on [Discord](https://discord.gg/Q6pZGSR).

Async scripts
-------------

This fork can run script queues on a thread other than the server's main thread, so that a script's own logic - tags, math, text, lists, maps - costs no main thread time and a slow script cannot lag the server.

### The one rule

**Crossing between an async script and the main thread costs up to a full tick, every time.**

Async moves CPU work. It does not move memory (garbage collection is shared and stop-the-world) and it does not move world access. Every command or tag that isn't safe off-thread is automatically handed to the main thread, and the script waits there - so scripts stay correct, they just gain nothing from those lines.

The win therefore comes from having *fewer* crossings, never from cheaper ones. Read live state before you go async, and keep only data processing inside.

### Starting async work

```
# A block. The script waits for it, and definitions it makes are ready on the next line.
- async:
    - define sorted <server.flag[scores].sort_by_value>
- narrate "Top: <[sorted].keys.last>"

# A whole script, in its own queue.
- run my_task async
- ~run my_task async save:r    # '~' waits for it; the server keeps running meanwhile

# One slow command, off-thread.
- ~define result <[huge_list].parse_tag[<[parse_value].to_uppercase>]>

# Background work that the script does not wait for.
- async detached copy_defs:path|index:
    - define point <[path].get[<[index]>].parsed>

# A scheduled run, on its own thread when the time comes. Survives a restart.
- runlater nightly_report delay:1h async
```

A block whose contents turn out to be trivial measures itself and simply runs on the main thread from then on, skipping the hand-off entirely - so a block that wasn't worth writing costs nothing for having been written.

### Safe off the main thread

**Commands** - queue and logic: `define`, `definemap`, `if`, `else`, `choose`, `foreach`, `while`, `repeat`, `goto`, `mark`, `inject`, `random`, `wait`, `waituntil`, `stop`, `determine`, `debug`, `async`. Files and network: `fileread`, `filewrite`, `filecopy`, `log`, `webget`, `yaml` - these touch nothing but disk and sockets, and without `~` they would otherwise block the main thread on I/O.

Databases: `sql`, `redis` - a socket to another server and their own connection lists, with no Minecraft server state in the path. One connection is still not thread-safe in itself, so two scripts working one id at the same time is the script writer's problem, exactly as it already was for two `~sql` lines.

Images: `image`, `draw` - pixel work and image files, with no server state anywhere in them. Building or resizing an image is exactly the kind of CPU work that has no business on the main thread.

`run` is safe when the line says `async`, since it then only starts a thread. A plain `run` still goes to the main thread, and the script it starts runs there: being called from an async script never makes a script async by itself.

**Tags** - anything that processes data rather than reading the server: elements, math, lists, maps, durations, text, `<util...>`, `<queue...>`, `<script...>`, definitions. Implementations may also exempt specific live-object tags that only read fields already stored on the object, or a whole object type where nothing it holds is live.

In Denizen that currently covers, among others: a location's arithmetic and its whole `<location[...]>` base; the geometry of cuboids, ellipsoids and polygons, and flags on any noted one; every tag on a biome; a material, an enchantment, a trade; and on a player, `uuid`, `name`, `is_online`, op/whitelist/ban status, first- and last-played times, and chat history.

A tag base written on its own - `<player>`, `<npc>` - is free too: it hands back an object the queue is already holding. Only reading *from* that object goes to the main thread.

### Not safe (handed to the main thread, script waits)

**Commands** - anything that changes or reads the live server: `flag`, `adjust`, `note`, `run` (without `async`), `runlater`, `queue`, `ratelimit`, `mongo`, `reload`, plus every world-touching command an implementation adds (teleport, spawn, give, ...).

`mongo` is listed here because it has not been tested off-thread, not because anything was found wrong with it - its own I/O already runs on a separate thread either way, so `~mongo` does not block the main thread regardless. `sql` and `redis` are built the same way and have since been marked safe: their threading has been measured off-thread (no hand-offs, and a failed connect releases its queue from the worker rather than hanging it), while the round trip of real query results is still waiting on a test against a live database.

**Tags** - anything reading live server, world, entity, player or plugin state. These are marked by the implementation and handed over automatically; you never get a wrong answer, you get a slow one.

### Fire-and-forget commands

Some commands only send something out and never report anything back. Those are handed over *without* the script waiting: their arguments, including all tags, are read on the script's own thread at the moment the script reaches the line, and only the sending is left for the main thread. The script carries on immediately, and deferred commands keep the order the script wrote them in.

Implementations mark these; in Denizen they are `narrate`, `actionbar`, `announce`, `playsound`, `playeffect`, `showfake`, `debugblock`, `toast`, `compass`, `fakeequip` and `sidebar`.

A command that builds its arguments and its execution into one generated step (`autoCompile`) cannot be handed over this way, since there is nothing left to split - that is what rules out `title`, `chat`, `blockcrack` and `tablist`.

A line is **not** handed over this way if the script is waiting for it anyway - `~`, a `save:` argument, or an `if:` argument - or if the command says this particular line can't be (for example `narrate ... per_player`, which has to parse its text once per target at the exact moment it sends).

### Diagnostics

```
- narrate "off-thread: <queue.is_async>, handoffs: <queue.async_stats.get[handoffs]>, waited: <queue.async_stats.get[wait_time]>"
```

`<QueueTag.async_stats>` is the number that answers "did async actually help?". Compare it against `<queue.time_ran>`: a queue that spent most of its life waiting would have run faster on the main thread. A queue that crossed more than once also reports this on completion in the debug log.

`<util.is_main_thread>` tells you where a tag is actually being read.

### Small examples

**Hoist the world read out of the loop.** This is where most of the real gain is:

```
# 20 crossings - one per pass, for the tag.
- async:
    - repeat 20:
        - narrate "hi <player.name>"

# 1 crossing - the name is read once, before the loop.
- define name <player.name>
- async:
    - repeat 20:
        - narrate "hi <[name]>"
```

**A loop of effects costs nothing:**

```
- define loc <player.location>
- async:
    - repeat 20:
        - playeffect effect:flame at:<[loc]> quantity:3
        - playsound <[loc]> sound:block_note_block_hat
```

**Slow data work, then use the result:**

```
- async:
    - define ranked <server.flag[scores].sort_by_value.reverse>
    - define top <[ranked].keys.first[10]>
- narrate "Top 10: <[top].comma_separated>"
```

**Background work you collect later:**

```
- async detached save:bg:
    - define report <[data].parse_tag[<[parse_value].to_titlecase>]>
# ... other work happens here, in parallel ...
- waituntil rate:1t max:30s <entry[bg].created_queue.state.equals[unknown]>
- narrate <[report]>
```

**What not to do** - this is slower than not using async at all:

```
- async:
    - foreach <server.online_players> as:p:
        - teleport <[p]> <[p].location.above[10]>
```

Every line here reads or changes the live world, so the block does nothing but pay for hand-offs.

### Measuring

Measure with `- ~run <script> async`, not with an `- async:` block. A cheap block runs inline on the main thread from its second execution onward, so `<queue.is_async>` reads false and every count is a false zero.

### Configuration

```yaml
Scripts:
    Async:
        Allow: true                    # false makes every async request run on the main thread instead
        Main thread wait timeout: 15s  # how long an async script waits for the main thread before erroring
        Shutdown timeout: 3s           # how long shutdown waits for async queues to finish
```

Each async queue owns a thread for its whole life, including while it sits in a `wait`. Starting them in a loop quietly turns into that many threads; the engine warns once when a lot are live at the same time. Prefer one queue that processes a list.

### Licensing pre-note:

This is an open source project, provided entirely freely, for everyone to use and contribute to.

If you make any changes that could benefit the community as a whole, please contribute upstream.

### The short of the license is:

You can do basically whatever you want, except you may not hold any developer liable for what you do with the software.

### Previous License

Copyright (C) 2014-2019 The Denizen Script Team, All Rights Reserved.

### The long version of the license follows:

The MIT License (MIT)

Copyright (c) 2019-2026 The Denizen Script Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

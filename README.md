DenizenCore
===========

The core Denizen engine

To be implemented and extended by separate DenizenScript projects.

Please posts issues to the [Denizen Repo](https://github.com/DenizenScript/Denizen/issues).

For API usage, refer to the [Denizen README](https://github.com/DenizenScript/Denizen).

If you are implementing your own version of Denizen using this core, this topic is not yet fully documented, but talk to us on [Discord](https://discord.gg/Q6pZGSR).

Async scripts
-------------

This fork can run script queues off the server's main thread, so a script's own logic - tags, math, text, lists, maps - costs no main thread time and a slow script cannot lag the server.

### The one rule

**The first crossing between an async script and the main thread costs up to a full tick. The ones behind it cost microseconds.**

Async moves CPU work. It does not move memory (garbage collection is shared and stop-the-world) and it does not move world access: anything not safe off-thread is handed to the main thread with the script waiting, so scripts stay correct and simply gain nothing from those lines.

The main thread serves those requests twice a tick, so the first one waits for that moment to come round. Having answered one it watches a moment longer, and a script's requests arrive back to back, so the rest are answered in microseconds. Measured on a live server: a hundred live reads in a row cost 3.7 seconds before that window existed, and 1-45ms after.

Fewer crossings is still the shape to aim for. What changed is the price of getting it wrong: a loop reading one live value per pass used to cost a tick per pass, which made it slower async than plain.

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

A block whose contents turn out to be trivial measures itself and runs on the main thread from then on, skipping the hand-off - so a block that wasn't worth writing costs nothing for having been written.

### Safe off the main thread

**Commands.** Queue and logic: `define`, `definemap`, `if`, `else`, `choose`, `foreach`, `while`, `repeat`, `goto`, `mark`, `inject`, `random`, `wait`, `waituntil`, `stop`, `determine`, `debug`, `async`.

Files, network and databases: `fileread`, `filewrite`, `filecopy`, `log`, `webget`, `yaml`, `sql`, `redis`, `webserver` - disk and sockets only, no server state in the path. Without `~` these would otherwise block the main thread on I/O. One connection is still not thread-safe in itself, so two scripts working one id at once is the script writer's problem, exactly as it already was.

Images: `image`, `draw` - pixel work and image files, the kind of CPU work that has no business on the main thread.

`schematic save`, and only that option: it walks blocks the plugin already holds into a file. The set is claimed while writing, so a `rotate` or `flip` refuses rather than rewriting blocks halfway through a save. `create` and `paste` read and write the world and stay on the main thread.

`flag`, when every target on the line keeps its flags in Denizen's own storage - the server, a player, a noted area or inventory, and the types living in the server's flag map (a material, an enchantment, a biome, a plugin, a script, a time). Any other target keeps its flags on the live object, so a line naming one is handed over whole: one crossing, targets still in order. A flag write publishes a rebuilt path rather than editing in place, so a reader on another thread can never walk a map while it is being restructured.

`ratelimit`, with one limit shared by every queue running that line rather than one growing per thread.

Sending to a client: `announce`, `narrate`, `actionbar`, `toast`, `debugblock`, `title`, `sidebar`, `tablist`, and `playeffect`/`playsound` when they name their targets - a player's connection queues packets from any thread. Excluded: `narrate`/`actionbar`/`title`/`sidebar` with `per_player` (reparse per target), `announce ... to_permission:` (asks a permissions plugin), and `playeffect`/`playsound` without `targets:` (must go looking for who is near enough). `playsound` gained `targets:` in this fork purely as an addition - lines written the old way behave exactly as before.

Looks like it belongs here but doesn't: `compass` and `showfake` read the world to do their job, and `fakeequip` looks up a live entity per target. All three are still fire-and-forget, so an async script never waits on them. `fakespawn` does cost a wait and cannot be split.

`run` is safe when the line says `async`, since it then only starts a thread. A plain `run` goes to the main thread and the script it starts runs there: being called from an async script never makes a script async by itself.

**Tags.** Anything processing data rather than reading the server: elements, math, lists, maps, durations, text, `<util...>`, `<queue...>`, `<script...>`, definitions. Implementations may also exempt live-object tags that only read fields already stored on the object, or a whole type where nothing it holds is live.

In Denizen that currently covers, among others: a location's arithmetic and its whole `<location[...]>` base; the geometry of cuboids, ellipsoids and polygons, and flags on any noted one; every tag on a biome, a material, an enchantment, a trade; on an item, everything stored on the stack itself - material, quantity, max stack size, durability, display name, lore, enchantments, book text, its own flags, and the item script it came from; on a player, `uuid`, `name`, `is_online`, op/whitelist/ban status, first- and last-played times, chat history, and fake blocks, fake entities and disguises; and the online player list.

A tag base written on its own - `<player>`, `<npc>` - is free too: it hands back an object the queue already holds. Only reading *from* that object crosses. The reverse also holds: a tag can be free while what it returns is not, so each extra step pays for that step. Reading a player's disguise costs nothing; asking the returned entity anything is a crossing.

### Not safe (handed to the main thread, script waits)

**Commands.** Anything that changes or reads the live server: `adjust`, `note`, `run` (without `async`), `queue`, `mongo`, `reload`, `reflectionset`, `customevent`, plus every world-touching command an implementation adds. `runlater` belongs here too, but the script does not wait on it - see below.

`customevent` looks like pure logic but fires arbitrary user script inline on the firing thread, so making it safe would turn one crossing into one per main-thread line of every handler. `mongo` is here because it has not been tested off-thread, not because anything was found wrong with it - its own I/O already runs on a separate thread, so `~mongo` does not block the main thread regardless.

**Tags.** Anything reading live server, world, entity, player or plugin state. These are marked by the implementation and handed over automatically: you never get a wrong answer, you get a slow one.

### Fire-and-forget commands

Some commands only send something out and never report anything back. Those are handed over *without* the script waiting: their arguments, including all tags, are read on the script's own thread at the line the script wrote, and only the sending is left for the main thread. The script carries on immediately, and deferred commands keep their order.

In core that is `runlater`; in Denizen, `narrate`, `actionbar`, `announce`, `playsound`, `playeffect`, `showfake`, `debugblock`, `toast`, `compass`, `fakeequip` and `sidebar`.

`runlater` sends nothing to anyone - what makes it fit is that it hands nothing back either. It only adds to a schedule the main thread walks every tick. The one difference worth knowing is that its delay is measured from when the main thread picks the command up, not from when the script asked - a tick or two, against a delay written in seconds. `runlater ... id:<...>` is excluded and still waits, since `id` is the one argument the command reads for itself rather than up front.

Being safe off the main thread is strictly better and is checked first, so for the commands listed as safe this only applies to a line they exclude. A command that builds its arguments and its execution into one generated step (`autoCompile`) cannot be split this way - that rules out `chat`, `blockcrack`, `tablist`, `fakespawn` and `bossbar`.

A line is **not** handed over this way if the script is waiting for it anyway (`~`, `save:`, or `if:`), or if the command says this particular line can't be.

### Diagnostics

```
- narrate "off-thread: <queue.is_async>, handoffs: <queue.async_stats.get[handoffs]>, waited: <queue.async_stats.get[wait_time]>"
```

`<QueueTag.async_stats>` answers "did async actually help?". Compare it against `<queue.time_ran>`: a queue that spent most of its life waiting would have run faster on the main thread. A queue that crossed more than once reports this on completion in the debug log.

`<queue.is_async>` answers for the queue as it actually ran: a queue the count limit pushed onto the main thread reports false, not true.

`<util.is_main_thread>` tells you where a tag is actually being read.

`<util.linger_stats>` reports what the main thread's wait window costs when it does not pay off - `idle_time` is time spent waiting for a follow-up request that never came, `count` is how many times the window was entered. Both only grow, so sample and subtract. Tune `Main thread wait linger us` against this rather than against TPS, which cannot see a few milliseconds a tick until the day there is no headroom left.

### Small examples

**Hoist the world read out of the loop.** This is where most of the real gain is:

```
# 20 crossings - one per pass, for the tag.
- async:
    - repeat 20:
        - narrate "hp: <player.health>"

# 1 crossing - the health is read once, before the loop.
- define hp <player.health>
- async:
    - repeat 20:
        - narrate "hp: <[hp]>"
```

Judge a tag by what it reads, not by how it looks. `<player.name>` and `<player.uuid>` cost nothing, because the engine keeps both itself; `<player.health>` asks the live entity every time. The `narrate` lines are free either way - it is the tag inside them being paid for.

**A loop of effects costs nothing:**

```
- define loc <player.location>
- async:
    - repeat 20:
        - playeffect effect:flame at:<[loc]> quantity:3
        - playsound <[loc]> sound:block_note_block_hat
```

Neither line makes the script wait. Naming who should see the particles - `targets:<player>` - goes one better and keeps the whole of `playeffect` off the main thread, since it no longer has to look up who is near enough.

**Slow data work, then use the result:**

```
- async:
    - define ranked <server.flag[scores].sort_by_value.reverse>
    - define top <[ranked].keys.first[10]>
- narrate "Top 10: <[top].comma_separated>"
```

**Background work you collect later:**

```
- async detached copy_defs:data save:bg:
    - define report <[data].parse_tag[<[parse_value].to_titlecase>]>
# ... other work happens here, in parallel ...
- waituntil rate:1t max:30s <entry[bg].created_queue.state.equals[unknown]>
- narrate <[report]>
```

A detached block runs alongside the script, so the two cannot share one set of definitions - it starts from a deep copy of what the queue holds. `copy_defs:` names the ones it needs and skips the rest, which is what makes this affordable inside a loop. What the block writes is merged back once it finishes, which is why the `waituntil` is what makes `<[report]>` safe to read and a plain `wait` is not.

**What not to do** - this is slower than not using async at all:

```
- async:
    - foreach <server.online_players> as:p:
        - teleport <[p]> <[p].location.above[10]>
```

The player list is free, but each pass then reads a live location and moves a player - two crossings per player, for work that has to happen on the main thread anyway.

### Measuring

Measure with `- ~run <script> async`, not with an `- async:` block. A cheap block runs inline on the main thread from its second execution onward, so `<queue.is_async>` reads false and every count is a false zero.

### Configuration

```yaml
Scripts:
    Async:
        Allow: true                    # false makes every async request run on the main thread instead
        Main thread wait timeout: 15s  # how long an async script waits for the main thread before erroring
        Shutdown timeout: 3s           # how long shutdown waits for async queues to finish
        Main thread task budget ms: 5  # per tick, for work handed over without waiting; 0 for no budget
        Main thread wait linger us: 500 # how long the main thread keeps watching for the next request after answering one; 0 to answer once per tick
        Warn at queue count: 50        # warn once when this many async queues are live; 0 to never warn
        Max queue count: 256           # past this, a new async queue runs on the main thread instead; 0 for no limit
```

Each async queue owns a thread for its whole life, including while it sits in a `wait`. Starting them in a loop quietly turns into that many threads; past the maximum a new one runs on the main thread instead, counted as it is dispatched so that a burst inside a single tick cannot slip past the limit. Prefer one queue that processes a list.

Work handed over *without* waiting - deferred commands, debug output - is budgeted per tick, because a script can produce it faster than the main thread can run it. The script never waits on any of it, so the budget costs it nothing; it only spreads the delivery.

Requests a script *is* waiting on are never budgeted, always served first, and the main thread does not leave the instant it has answered them. It keeps watching for `Main thread wait linger us`, because the next request is usually microseconds behind the last. Every answer restarts the window, and a hard limit of 5ms per pass stops a script asking in a tight loop from holding the tick open.

That default was measured in both directions. Dropping it to 100us cost 23x the waiting for the same work; raising it to 2000us saved about a third of what remained, but quadrupled what the window costs the main thread when nothing follows - 2ms per drain instead of 0.5ms, up to twice a tick. Raise it only if your server has headroom to spare and its scripts read live values often, and check what it actually costs you with `<util.linger_stats>`.

**One thing off-thread scripts do not get:** reading another queue's definitions - `<queue[some_id].definition[x]>` - while that queue runs off-thread has no guarantees. Definitions are an ordinary ordered map, and making it otherwise would cost every definition read in every script to protect an unusual one. Read your own freely; to get a value out of a queue you don't own, have it write a flag or use `- async:`'s own definition merging.

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

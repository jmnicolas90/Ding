# Contributing

You are welcome to contribute to Ding. This file is about code contributions. For other ways to help, see the [Readme](README.md#feedback).

Note that each contribution is implicitly made under the project's license (see LICENSE.md). If you want to make a contribution under a different license, contact the maintainer.

## Contribution guidelines

### Procedure

To get the best results and avoid problems, please follow these steps:
1. First express your intentions using issues. Maybe there are already plans or work has been started on something you want to do.
2. We will discuss the issue. Maybe I will take part in implementation, giving feedback on the planned design or even write skeletons/APIs for you so you don't have to deal with too many internals.
3. When we have decided what to do, you can fork the project and send a pull request when ready.

### Creating and changing files

Ding is a fork, so a file's copyright header records who wrote it, not who owns the project:

- **A new file carries the fork's copyright line**, followed by the GPL notice as in the rest of the project:

      Copyright (C) 2026 Jean-Michel Nicolas

  In a file where `/* */` or `<!-- -->` is not available, use the comment character the file's language provides (`#` for shell scripts and YAML, for instance) and keep the same wording.
- **Headers inherited from SimpleReminder are never removed or edited**, including their copyright years. They are the attribution the GPL requires. A file the fork changes substantially may gain the fork's copyright line *underneath* upstream's; a file the fork only touches lightly keeps upstream's header alone.
- Never put an email address in a header, or anywhere else in the tree. The quality gate fails on one.

## Attribution ##
For non-minor contributions you will be added to the [CONTRIBUTORS.md](CONTRIBUTORS.md) list with a link to your Github account.
You will be asked whether you want to use a different name than your Github display name.

<!--  LocalWords:  Readme APIs Github
 -->

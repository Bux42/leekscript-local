# LeekScript

### Add user code definition support

This is a fork from the original leekscript repository
The goal of this fork is to modify the compiler code and add features to "extract" the user code definitions (classes, functions, variables, etc) to then be used by a vscode extension

The compiler's behavior is unchanged, as a new entrypoint method "getDefinitions" has been added, to keep the original code unchanged

The definition extractor keeps track of opening & closing blocks during the building process of the Abstract Syntax Tree, it only takes two input variables, the user's current opened file and the cursor line & column position (that way, the extractor will not add a definition for something "out of scope")

[![CI](https://github.com/leek-wars/leekscript/actions/workflows/build.yml/badge.svg)](https://github.com/leek-wars/leekscript/actions/workflows/build.yml)

The language of Leek Wars, built in Java.
Used in the [leek-wars-generator](https://github.com/leek-wars/leek-wars-generator) project.

### Build

```
gradle jar
```

### Run a console

```
java -jar leekscript.jar
```

### Build and run tests

```
gradle jar test
```

### Credits

Developed by Dawyde & Pilow © 2012-2022

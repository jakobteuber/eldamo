# Eldamo

An Elvish Data Model in XML.

Most consumers of this project are probably interested in the finished product, the generated Elvish lexicon. This can
be found at https://eldamo.org (mirror https://pfstrack.github.io/eldamo/) and project releases are zip files of this site.

This document provides information for those interested in how the Eldamo site is generated. To fully understand this
process, you need to know:

* Java
* XML editing and parsing, include XSL and XQuery

## Requirements

1. Git
2. Java 25+ (the Gradle wrapper downloads everything else)
3. A good text editor (I use TextWrangler on OS X).
4. For generating the site, a web crawler (I use SiteSucker on OS X)

## Command-line Execution

1) Clone the project from github:

```
git clone https://github.com/pfstrack/eldamo.git
```

2) Copy the data directory inside the web application directory:

```
cp -R src/data src/main/webapp
```

Or simply manually copy the directory.

3) Start the development server:

```
./gradlew run
```

4) Browse the site:

```
http://localhost:8080/eldamo/
```

5) Edit the data file (src/main/webapp/eldamo-data.xml) and refresh the pages to see changes.

## Using an IDE

Import the project as a Gradle project (Eclipse, IntelliJ IDEA, …) and run `eldamo.Main` with the project directory as
working directory.

## Building the Eldamo Site

```
./gradlew release
```

This writes the published ("pub") version of the site to `build/site`: the static files, the home page and every page
listed by `config/query-configs/site-pages.xq`. The published version can also be browsed on the development server at
http://localhost:8080/eldamo/pub/index.html.

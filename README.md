# Litho [![GithubCI](https://github.com/facebook/litho/actions/workflows/ci.yml/badge.svg)](https://github.com/facebook/litho/actions/workflows/ci.yml) [![Bintray](https://img.shields.io/maven-metadata/v/https/jcenter.bintray.com/com/facebook/litho/litho-core/maven-metadata.xml.svg?color=orange&label=bintray)](https://bintray.com/facebook/maven/com.facebook.litho%3Alitho-core/_latestVersion) [![Join the chat at https://gitter.im/facebook/litho](https://badges.gitter.im/facebook/litho.svg)](https://gitter.im/facebook/litho?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/facebook/litho/blob/master/LICENSE)

<img src="website/static/logo.png" width=150 align=right>

Litho is a declarative framework for building efficient UIs on Android.

* **Declarative:** Litho uses a declarative API to define UI components. You simply describe the layout for your UI based on a set of immutable inputs and the framework takes care of the rest.
* **Asynchronous layout:** Litho can measure and layout your UI ahead of time without blocking the UI thread.
* **View flattening:** Litho uses [Yoga](https://www.yogalayout.dev/) for layout and automatically reduces the number of ViewGroups that your UI contains.
* **Fine-grained recycling:** Any component such as a text or image can be recycled and reused anywhere in the UI.

To get started, check out these links:

* [Learn how to use Litho in your project.](http://fblitho.com/docs/getting-started)
* [Get started with our tutorial.](http://fblitho.com/docs/tutorial)
* [Read more about Litho in our docs.](http://fblitho.com/docs/intro)

## Installation
Litho can be integrated either in Gradle or Buck projects. Read our [Getting Started](http://fblitho.com/docs/getting-started) guide for installation instructions.

## Quick start
### 1. Initialize `SoLoader` in your `Application` class.
```java
public class SampleApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    SoLoader.init(this, false);
  }
}
```
### 2. Create and display a component in your Activity
```java
@Override
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final ComponentContext c = new ComponentContext(this);

    final Component component = Text.create(c)
        .text("Hello World")
        .textSizeDip(50)
        .build();

    setContentView(LithoView.create(c, component));
}
```
## Run sample
You can find more examples in our [sample app](https://github.com/facebook/litho/tree/master/sample).

To build and run (on an attached device/emulator) the sample app, execute

    $ buck fetch sample
    $ buck install -r sample

or, if you prefer Gradle,

    $ ./gradlew :sample:installDebug

## Contributing

Before contributing to Litho, please first read the [Code of
Conduct](https://code.facebook.com/codeofconduct) that we expect
project participants to adhere to.

For pull requests, please see our [CONTRIBUTING](CONTRIBUTING.md) guide.

See our [issues](https://github.com/facebook/litho/issues/) page for ideas on how to contribute or to let us know of any problems.

Please also read our [Coding Style](https://github.com/facebook/litho/blob/master/CONTRIBUTING.md#coding-style) and [Code of Conduct](https://code.facebook.com/codeofconduct) before you contribute.

## Getting Help

- Post on [StackOverflow](https://stackoverflow.com/questions/tagged/litho)
  using the `#litho` tag.
- Chat with us on [Gitter](https://gitter.im/facebook/litho).
- Join our [Facebook Group](https://www.facebook.com/groups/litho.android/) to
  stay up-to-date with announcements.
- Please open GitHub issues only if you suspect a bug in the framework or have a
  feature request and not for general questions.

## License

Litho is licensed under the [Apache 2.0 License](LICENSE).

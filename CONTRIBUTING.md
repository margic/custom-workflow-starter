# Contributing to Anax Kogito Starter

Thank you for considering contributing to the Anax Kogito Starter! This document provides guidelines and instructions for contributing.

## Code of Conduct

By participating in this project, you agree to abide by our Code of Conduct (see CODE_OF_CONDUCT.md).

## How to Contribute

### Reporting Bugs

If you find a bug, please create an issue with:
- A clear, descriptive title
- Steps to reproduce the issue
- Expected behavior vs actual behavior
- Your environment (Java version, Gradle version, OS)
- Relevant code snippets or error messages

### Suggesting Enhancements

Enhancement suggestions are welcome! Please create an issue with:
- A clear description of the enhancement
- Use cases and benefits
- Possible implementation approach

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Add tests for your changes
5. Ensure all tests pass (`./gradlew test`)
6. Commit your changes (`git commit -m 'Add amazing feature'`)
7. Push to your branch (`git push origin feature/amazing-feature`)
8. Open a Pull Request

#### Pull Request Guidelines

- Follow the existing code style
- Write clear commit messages
- Include tests for new functionality
- Update documentation as needed
- Keep PRs focused - one feature/fix per PR

## Development Setup

### Prerequisites

- Java 17 or higher
- Gradle 8.10+ (or use the wrapper: `./gradlew`)

### Building from Source

```bash
git clone https://github.com/margic/anax-kogito-starter.git
cd anax-kogito-starter
./gradlew build
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :anax-kogito-codegen-extensions:test
```

### Local Development

To test your changes locally:

```bash
./gradlew publishToMavenLocal
```

This publishes all modules to `~/.m2/repository` for testing in other projects.

## Module Structure

- **anax-kogito-codegen-extensions**: Build-time SPI for custom URI schemes
- **anax-kogito-spring-boot-starter**: Runtime auto-configuration and handlers
- **anax-kogito-codegen-plugin**: Gradle plugin for code generation
- **anax-kogito-sample**: Example application

## Coding Standards

- Follow standard Java naming conventions
- Use meaningful variable and method names
- Add JavaDoc comments for public APIs
- Keep methods focused and reasonably sized
- Write tests for new functionality

## Questions?

Feel free to open an issue with your question or reach out to the maintainers.

Thank you for contributing! 🎉

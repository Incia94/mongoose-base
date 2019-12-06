# Installer

# Contents

1. [Introduction](#introduction)
2. [Installer](#installer)
3. [Stages](#stages)

# Introduction

To use any Mongoose extension it should be placed to the `<USER_HOME_DIR>/.mongoose/<VERSION>/ext` directory.
Any file under this directory having the filename suffix `.jar` either `.zip` is loaded by the Mongoose's classloader
and its content becomes available in the runtime. This allows to use not only Mongoose's extensions but also the 
3rd party scenario engine implementations or credential providers.

## Installer

Mongoose's extensions may use the installer hook to install the additional configuration files (for example).
The installer hook is invoked each time Mongoose runs. However, installer may do no persistent changes if all
required/supplementary files are already installed and their content is the same.

### Stages

0. Installer loads the default configuration from the resources to determine the version. The version is used to
determine the Mongoose home path which is `<USER_HOME_DIR>/.mongoose/<VERSION>`.

1. Installer copies the required and supplementary files into the Mongoose home. These files are default configuration,
custom content files, scenarios and extensions.

**Note:** the file is not overwritten if the file with the same name already exists in the Mongoose home directory and
has the same MD5 checksum.

2. Initial configuration schema is being resolved and loaded.

3. Initial default configuration is being loaded.

4. Installer copies the resolved Mongoose extensions to the `<USER_HOME_DIR>/.mongoose/<VERSION>/ext` directory.

5. Then each extension installs itself. The Mongoose home directory is passed as an argument for the extension installer
hook. The extension inherits the basic installer functionality to copy the specific files for the given extension.

6. Each extension provides its own defaults configuration (if any) from the installed file
(usually `<USER_HOME_DIR>/.mongoose/<VERSION>/config/defaults****.yaml`).

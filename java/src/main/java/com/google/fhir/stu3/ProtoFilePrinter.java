//    Copyright 2018 Google Inc.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        https://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package com.google.fhir.stu3;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import com.google.fhir.proto.PackageInfo;
import com.google.fhir.stu3.proto.Annotations;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueOptions;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A utility to turn protocol message descriptors into .proto files. */
public class ProtoFilePrinter {

  private static final String APACHE_LICENSE =
      "//    Copyright %1$s Google Inc.\n"
          + "//\n"
          + "//    Licensed under the Apache License, Version 2.0 (the \"License\");\n"
          + "//    you may not use this file except in compliance with the License.\n"
          + "//    You may obtain a copy of the License at\n"
          + "//\n"
          + "//        https://www.apache.org/licenses/LICENSE-2.0\n"
          + "//\n"
          + "//    Unless required by applicable law or agreed to in writing, software\n"
          + "//    distributed under the License is distributed on an \"AS IS\" BASIS,\n"
          + "//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n"
          + "//    See the License for the specific language governing permissions and\n"
          + "//    limitations under the License.\n";

  // The proto package that contains the annotation definitions for proto options.
  // TODO: Move the annotation to a general location.
  static final String ANNOTATION_PACKAGE = "google.fhir.stu3.proto";

  private final PackageInfo packageInfo;

  private static final Escaper VALUE_REGEX_ESCAPER =
      new CharEscaperBuilder().addEscape('\\', "\\\\").toEscaper();

  /** Creates a ProtoFilePrinter with default parameters. */
  public ProtoFilePrinter() {
    this(PackageInfo.getDefaultInstance());
  }

  /** Creates a ProtoFilePrinter with default parameters. */
  public ProtoFilePrinter(PackageInfo packageInfo) {
    this.packageInfo = packageInfo;
  }

  /** Generate a .proto file corresponding to the provided FileDescriptorProto. */
  public String print(FileDescriptorProto fileDescriptor) {
    String fullyQualifiedPackageName = "." + fileDescriptor.getPackage();
    StringBuilder contents = new StringBuilder();
    if (packageInfo.getLicense() == PackageInfo.License.APACHE) {
      String licenseDate =
          packageInfo.getLicenseDate().isEmpty()
              ? ("" + new GregorianCalendar().get(Calendar.YEAR))
              : packageInfo.getLicenseDate();
      contents.append(String.format(APACHE_LICENSE, licenseDate)).append("\n");
    }
    contents.append(printHeader(fileDescriptor)).append("\n");
    contents.append(printImports(fileDescriptor)).append("\n");
    contents.append(printOptions(fileDescriptor)).append("\n");
    for (DescriptorProto descriptor : fileDescriptor.getMessageTypeList()) {
      contents
          .append(printMessage(descriptor, fullyQualifiedPackageName, fullyQualifiedPackageName))
          .append("\n");
    }
    return contents.toString();
  }

  private String printHeader(FileDescriptorProto fileDescriptor) {
    StringBuilder header = new StringBuilder();
    if (fileDescriptor.hasSyntax()) {
      header.append("syntax = \"").append(fileDescriptor.getSyntax()).append("\";\n\n");
    }
    if (fileDescriptor.hasPackage()) {
      header.append("package ").append(fileDescriptor.getPackage()).append(";\n");
    }
    return header.toString();
  }

  private String printImports(FileDescriptorProto fileDescriptor) {
    final StringBuilder imports = new StringBuilder();
    fileDescriptor.getDependencyList().stream()
        .sorted()
        .forEach(dependency -> imports.append("import \"").append(dependency).append("\";\n"));
    return imports.toString();
  }

  private String printOptions(FileDescriptorProto fileDescriptor) {
    StringBuilder options = new StringBuilder();
    FileOptions fileOptions = fileDescriptor.getOptions();
    if (fileOptions.hasJavaMultipleFiles()) {
      options
          .append("option java_multiple_files = ")
          .append(fileOptions.getJavaMultipleFiles())
          .append(";\n");
    }
    if (fileOptions.hasJavaPackage()) {
      options
          .append("option java_package = \"")
          .append(fileOptions.getJavaPackage())
          .append("\";\n");
    }
    return options.toString();
  }

  private String printMessage(DescriptorProto descriptor, String typePrefix, String packageName) {
    // Get the name of this message.
    String messageName = descriptor.getName();
    String fullName = typePrefix + "." + messageName;
    MessageOptions options = descriptor.getOptions();

    CharMatcher matcher = CharMatcher.is('.');
    String indent =
        Strings.repeat("  ", matcher.countIn(typePrefix) - matcher.countIn(packageName));
    StringBuilder message = new StringBuilder();

    if (options.hasExtension(Annotations.messageDescription)) {
      // Add the main documentation.
      message
          .append(indent)
          .append("// ")
          .append(
              options
                  .getExtension(Annotations.messageDescription)
                  .replaceAll("[\\n\\r]", "\n" + indent + "// "))
          .append("\n");
    }

    // Start the main message.
    message.append(indent).append("message ").append(messageName).append(" {\n");

    String fieldIndent = indent + "  ";
    boolean printedField = false;

    // Add options.
    // For fhir options, only fully type the package name if we are not writing to the core FHIR
    // package.
    String optionPackage =
        packageName.equals("." + ANNOTATION_PACKAGE) ? "" : "." + ANNOTATION_PACKAGE + ".";
    if (options.hasExtension(Annotations.structureDefinitionKind)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("structure_definition_kind) = ")
          .append(options.getExtension(Annotations.structureDefinitionKind))
          .append(";\n");
      printedField = true;
    }
    if (options.hasExtension(Annotations.fhirValuesetUrl)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("fhir_valueset_url) = \"")
          .append(options.getExtension(Annotations.fhirValuesetUrl))
          .append("\";\n");
      printedField = true;
    }
    if (options.hasExtension(Annotations.isAbstractType)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("is_abstract_type) = ")
          .append(options.getExtension(Annotations.isAbstractType))
          .append(";\n");
      printedField = true;
    }
    if (options.hasExtension(Annotations.valueRegex)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("value_regex) = \"")
          .append(VALUE_REGEX_ESCAPER.escape(options.getExtension(Annotations.valueRegex)))
          .append("\";\n");
      printedField = true;
    }
    for (int i = 0; i < options.getExtensionCount(Annotations.fhirProfileBase); i++) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("fhir_profile_base) = \"")
          .append(options.getExtension(Annotations.fhirProfileBase, i))
          .append("\";\n");
      printedField = true;
    }
    if (options.hasExtension(Annotations.fhirStructureDefinitionUrl)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("fhir_structure_definition_url) = \"")
          .append(options.getExtension(Annotations.fhirStructureDefinitionUrl))
          .append("\";\n");
      printedField = true;
    }
    if (options.hasExtension(Annotations.isChoiceType)) {
      message
          .append(fieldIndent)
          .append("option (")
          .append(optionPackage)
          .append("is_choice_type) = ")
          .append(options.getExtension(Annotations.isChoiceType))
          .append(";\n");
      printedField = true;
    }

    // Loop over the elements.
    Set<String> printedNestedTypeDefinitions = new HashSet<>();
    for (FieldDescriptorProto field : descriptor.getFieldList()) {
      if (!field.hasOneofIndex()) {
        // Keep a newline between fields.
        if (printedField) {
          message.append("\n");
        }

        if (field.getOptions().hasExtension(Annotations.fieldDescription)) {
          // Add a comment describing the field.
          String description = field.getOptions().getExtension(Annotations.fieldDescription);
          message
              .append(fieldIndent)
              .append("// ")
              .append(description.replaceAll("[\\n\\r]", "\n" + indent + "// "))
              .append("\n");
        }

        // Add nested types if necessary.
        message.append(
            maybePrintNestedType(
                descriptor, field, typePrefix, packageName, printedNestedTypeDefinitions));
        message.append(printField(field, fullName, fieldIndent, optionPackage));
        printedField = true;
      }
    }

    // Loop over the oneofs
    String oneofIndent = fieldIndent + "  ";
    for (int oneofIndex = 0; oneofIndex < descriptor.getOneofDeclCount(); oneofIndex++) {
      if (printedField) {
        message.append("\n");
      }
      message
          .append(fieldIndent)
          .append("oneof ")
          .append(descriptor.getOneofDecl(oneofIndex).getName())
          .append(" {\n");
      // Loop over the elements.
      for (FieldDescriptorProto field : descriptor.getFieldList()) {
        if (field.getOneofIndex() == oneofIndex) {
          message.append(printField(field, fullName, oneofIndent, optionPackage));
        }
      }
      message.append(fieldIndent).append("}\n");
    }

    // Print any enums that weren't already printed (i.e., enums that aren't used by any fields)
    for (EnumDescriptorProto enumProto : descriptor.getEnumTypeList()) {
      if (printedField) {
        message.append("\n");
      }
      String fullTypeName = fullName + "." + enumProto.getName();
      if (printedNestedTypeDefinitions.add(fullTypeName)) {
        message.append(printEnum(enumProto, fullTypeName, packageName));
      }
    }

    // Close the main message.
    message.append(indent).append("}\n");

    return message.toString();
  }

  private String printEnum(EnumDescriptorProto descriptor, String typePrefix, String packageName) {
    // Get the name of this message.
    String messageName = descriptor.getName();

    CharMatcher matcher = CharMatcher.is('.');
    String indent =
        Strings.repeat("  ", matcher.countIn(typePrefix) - matcher.countIn(packageName));
    StringBuilder message = new StringBuilder();

    // Start the enum.
    message.append(indent).append("enum ").append(messageName).append(" {\n");

    String fieldIndent = indent + "  ";

    // Loop over the elements.
    for (EnumValueDescriptorProto field : descriptor.getValueList()) {
      message.append(fieldIndent).append(field.getName()).append(" = ").append(field.getNumber());

      boolean hasFieldOption = false;
      EnumValueOptions options = field.getOptions();
      if (options.hasExtension(Annotations.fhirOriginalCode)) {
        hasFieldOption =
            addFieldOption(
                "(fhir_original_code)",
                "\"" + options.getExtension(Annotations.fhirOriginalCode) + "\"",
                hasFieldOption,
                message);
      }
      if (hasFieldOption) {
        message.append("]");
      }

      message.append(";\n");
    }

    // Close the enum.
    message.append(indent).append("}\n");

    return message.toString();
  }

  private String maybePrintNestedType(
      DescriptorProto descriptor,
      FieldDescriptorProto field,
      String typePrefix,
      String packageName,
      Set<String> printedNestedTypeDefinitions) {
    String prefix = typePrefix + "." + descriptor.getName();
    if (field.hasTypeName()
        && field.getTypeName().startsWith(prefix + ".")
        && !printedNestedTypeDefinitions.contains(field.getTypeName())) {
      List<String> typeNameParts = Splitter.on('.').splitToList(field.getTypeName());
      String typeName = typeNameParts.get(typeNameParts.size() - 1);
      if (field.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE) {
        for (DescriptorProto nested : descriptor.getNestedTypeList()) {
          if (nested.getName().equals(typeName)) {
            printedNestedTypeDefinitions.add(field.getTypeName());
            return printMessage(nested, prefix, packageName);
          }
        }
      } else if (field.getType() == FieldDescriptorProto.Type.TYPE_ENUM) {
        for (EnumDescriptorProto nested : descriptor.getEnumTypeList()) {
          if (nested.getName().equals(typeName)) {
            printedNestedTypeDefinitions.add(field.getTypeName());
            return printEnum(nested, prefix, packageName);
          }
        }
      }
    }
    // The type wasn't defined in this scope, print nothing.
    return "";
  }

  // Build the field.
  private String printField(
      FieldDescriptorProto field, String containingType, String indent, String optionPackage) {
    StringBuilder message = new StringBuilder();

    // Add the "repeated" keyword, if necessary.
    message.append(indent);
    if (field.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED) {
      message.append("repeated ");
    }

    // Add the type of the field.
    if ((field.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE
            || field.getType() == FieldDescriptorProto.Type.TYPE_ENUM)
        && field.hasTypeName()) {
      List<String> typeNameParts = Splitter.on('.').splitToList(field.getTypeName());
      List<String> containingTypeParts = Splitter.on('.').splitToList(containingType);
      int numCommon = 0;
      while (numCommon < typeNameParts.size()
          && numCommon < containingTypeParts.size()
          && typeNameParts.get(numCommon).equals(containingTypeParts.get(numCommon))) {
        numCommon++;
      }

      // never drop the last token.
      // E.g., foo.baz.Quux on foo.baz.Quux (recursively) shortens to Quux.
      int tokensToDrop = numCommon == typeNameParts.size() ? numCommon - 1 : numCommon;

      // Make sure the first token that is not dropped in the shortened name is not a token
      // somewhere else in non-common part of the containing type.
      // E.g., if "abc.foo.baz.Quux" is a type used in "abc.foo.bar.baz.Bleh",
      // and we try to shorten it to baz.Quux, it will look for Quux on abc.foor.bar.baz,
      // and will error. In this case, just print the fully-qualified name.
      if (containingTypeParts
          .subList(numCommon, containingTypeParts.size())
          .contains(typeNameParts.get(tokensToDrop))) {
        tokensToDrop = 0;
      }

      // Since absolute namespaces start with ".", the first token is is empty (and thus common).
      // If this is the only common token, don't drop anything
      if (tokensToDrop > 1) {
        message.append(
            Joiner.on('.').join(typeNameParts.subList(tokensToDrop, typeNameParts.size())));
      } else {
        message.append(field.getTypeName());
      }
    } else if (field.getType().toString().startsWith("TYPE_")) {
      message.append(field.getType().toString().substring(5).toLowerCase());
    } else {
      message.append("INVALID_TYPE");
    }

    // Add the name and field number.
    message.append(" ").append(field.getName()).append(" = ").append(field.getNumber());

    FieldOptions options = field.getOptions();
    boolean hasFieldOption = false;
    if (options.hasExtension(Annotations.validationRequirement)) {
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "validation_requirement)",
              options.getExtension(Annotations.validationRequirement).toString(),
              hasFieldOption,
              message);
    }
    if (options.hasExtension(Annotations.fhirInlinedExtensionUrl)) {
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "fhir_inlined_extension_url)",
              "\"" + options.getExtension(Annotations.fhirInlinedExtensionUrl) + "\"",
              hasFieldOption,
              message);
    }
    if (options.hasExtension(Annotations.fhirInlinedCodingSystem)) {
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "fhir_inlined_coding_system)",
              "\"" + options.getExtension(Annotations.fhirInlinedCodingSystem) + "\"",
              hasFieldOption,
              message);
    }
    if (options.hasExtension(Annotations.fhirInlinedCodingCode)) {
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "fhir_inlined_coding_code)",
              "\"" + options.getExtension(Annotations.fhirInlinedCodingCode) + "\"",
              hasFieldOption,
              message);
    }
    for (int i = 0; i < options.getExtensionCount(Annotations.validReferenceType); i++) {
      String type = options.getExtension(Annotations.validReferenceType, i);
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "valid_reference_type)",
              "\"" + type + "\"",
              hasFieldOption,
              message);
    }

    if (field.hasJsonName()) {
      hasFieldOption =
          addFieldOption("json_name", "\"" + field.getJsonName() + "\"", hasFieldOption, message);
    }

    for (int i = 0; i < options.getExtensionCount(Annotations.fhirPathConstraint); i++) {
      String fhirPathConstraint = options.getExtension(Annotations.fhirPathConstraint, i);
      hasFieldOption =
          addFieldOption(
              "(" + optionPackage + "fhir_path_constraint)",
              "\"" + fhirPathConstraint + "\"",
              hasFieldOption,
              message);
    }

    if (hasFieldOption) {
      message.append("]");
    }

    return message.append(";\n").toString();
  }

  private boolean addFieldOption(
      String option, String value, boolean hasFieldOption, StringBuilder message) {
    if (!hasFieldOption) {
      message.append(" [");
      hasFieldOption = true;
    } else {
      message.append(", ");
    }
    message.append(option).append(" = ").append(value);
    return true;
  }
}

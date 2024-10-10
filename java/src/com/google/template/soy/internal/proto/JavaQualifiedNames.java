
package com.google.template.soy.internal.proto;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Helper class for generating fully qualified Java/GWT identifiers for descriptors. */
public final class JavaQualifiedNames {
    private JavaQualifiedNames() {}

    /** Returns the expected java package for protos based on the .proto file. */
    public static String getPackage(Descriptors.FileDescriptor fileDescriptor) {
        return getPackage(fileDescriptor, ProtoFlavor.PROTO2);
    }

    static String getPackage(FileDescriptor file, ProtoFlavor flavor) {
        return getPackage(file.toProto(), flavor);
    }

    static String getPackage(FileDescriptorProto file, ProtoFlavor flavor) {
        StringBuilder sb = new StringBuilder();
        FileOptions fileOptions = file.getOptions();
        appendJavaPackage(sb, fileOptions);
        appendProtoPackage(sb, file);
        return sb.toString();
    }

    private static void appendJavaPackage(StringBuilder sb, FileOptions fileOptions) {
        if (fileOptions.hasJavaPackage()) {
            sb.append(fileOptions.getJavaPackage());
        }
    }

    private static void appendProtoPackage(StringBuilder sb, FileDescriptorProto file) {
        if (!file.getPackage().isEmpty()) {
            sb.append(file.getPackage());
        }
    }

    /** Derives the outer class name based on the protobuf (.proto) file name. */
    public static String getOuterClassname(Descriptors.FileDescriptor fileDescriptor) {
        return getFileClassName(fileDescriptor, ProtoFlavor.PROTO2);
    }

    /** Returns the fully-qualified name for the message descriptor (uses '.' inner class separator). */
    public static String getQualifiedName(Descriptors.Descriptor msg) {
        return getClassName(msg).replace('$', '.');
    }

    /** Returns the fully-qualified name for the enum descriptor (uses '.' inner class separator). */
    public static String getQualifiedName(Descriptors.EnumDescriptor enumType) {
        return getClassName(enumType).replace('$', '.');
    }

    /**
     * Returns the fully-qualified name for the message descriptor with the given flavor (uses '.'
     * inner class separator).
     */
    public static String getQualifiedName(Descriptors.Descriptor msg, ProtoFlavor flavor) {
        return getClassName(msg, flavor).replace('$', '.');
    }

    /**
     * Returns the fully-qualified name for the enum descriptor with the given flavor (uses '.' inner
     * class separator).
     */
    public static String getQualifiedName(Descriptors.EnumDescriptor enumType, ProtoFlavor flavor) {
        return getClassName(enumType, flavor).replace('$', '.');
    }

    /** Returns the class name for the message descriptor (uses '$' inner class separator). */
    public static String getClassName(Descriptors.Descriptor msg) {
        return getClassName(msg, ProtoFlavor.PROTO2);
    }

    /** Returns the class name for the enum descriptor (uses '$' inner class separator). */
    public static String getClassName(Descriptors.EnumDescriptor enumType) {
        return getClassName(enumType, ProtoFlavor.PROTO2);
    }

    public static String getClassName(Descriptor descriptor, ProtoFlavor flavor) {
        return getClassName(classNameWithoutPackage(descriptor, flavor), descriptor.getFile(), flavor);
    }

    public static String getClassName(EnumDescriptor descriptor, ProtoFlavor flavor) {
        return getClassName(classNameWithoutPackage(descriptor, flavor), descriptor.getFile(), flavor);
    }

    private static String getClassName(String nameWithoutPackage, FileDescriptor file, ProtoFlavor flavor) {
        StringBuilder sb = new StringBuilder();
        appendClassName(sb, file, flavor);
        sb.append(nameWithoutPackage.replace('.', '$'));
        return sb.toString();
    }

    private static void appendClassName(StringBuilder sb, FileDescriptor file, ProtoFlavor flavor) {
        if (multipleJavaFiles(file, flavor)) {
            sb.append(getPackage(file, flavor));
            if (sb.length() > 0) {
                sb.append('.');
            }
        } else {
            sb.append(getClassName(file, flavor));
            if (sb.length() > 0) {
                sb.append('$');
            }
        }
    }

    private static String getClassName(FileDescriptor file, ProtoFlavor flavor) {
        StringBuilder sb = new StringBuilder();
        sb.append(getPackage(file, flavor));
        if (sb.length() > 0) {
            sb.append('.');
        }
        sb.append(getFileClassName(file, flavor));
        return sb.toString();
    }

    private static final String[] forbiddenWords = {
        "InitializationErrorString", "CachedSize", "Class", "SerializedSize",
        "DefaultInstanceForType", "ParserForType", "AllFields", "DescriptorForType", "UnknownFields"
    };

    private static final Set<String> specialCases = new HashSet<>(Arrays.asList(forbiddenWords));

    static {
        for (String word : forbiddenWords) {
            String lowerCase = word.toLowerCase(Locale.US).charAt(0) + word.substring(1);
            specialCases.add(lowerCase);
        }
    }

    /** Returns the Java name for a proto field. */
    public static String getFieldName(Descriptors.FieldDescriptor field, boolean capitalizeFirstLetter) {
        String fieldName = field.getName();
        String javaName = underscoresToCamelCase(handleFieldConflict(field, fieldName), capitalizeFirstLetter);
        return specialCases.contains(javaName) ? javaName + '_' : javaName;
    }

    private static String handleFieldConflict(Descriptors.FieldDescriptor field, String fieldName) {
        if (fieldConflict(field)) {
            return fieldName + field.getNumber();
        }
        return fieldName;
    }

    private static boolean fieldConflict(Descriptors.FieldDescriptor field) {
        Descriptor message = field.getContainingType();
        if (field.isRepeated()) {
            return checkRepeatedFieldConflict(field, message);
        } else if (field.getName().endsWith("_count")) {
            return checkScalarFieldConflict(field, message);
        }
        return false;
    }

    private static boolean checkRepeatedFieldConflict(Descriptors.FieldDescriptor field, Descriptor message) {
        return message.findFieldByName(field.getName() + "_count") != null;
    }

    private static boolean checkScalarFieldConflict(Descriptors.FieldDescriptor field, Descriptor message) {
        return message.findFieldByName(field.getName().substring(0, field.getName().length() - "_count".length())) != null;
    }

    /** Returns the class name for the enum descriptor (uses '$' inner class separator). */
    public static String getCaseEnumClassName(Descriptors.OneofDescriptor oneOfDescriptor) {
        return getClassName(oneOfDescriptor.getContainingType())
            + '$'
            + underscoresToCamelCase(oneOfDescriptor.getName(), true)
            + "Case";
    }

    /** Converts underscore field names to camel case, while preserving camel case field names. */
    public static String underscoresToCamelCase(String input, boolean capitalizeNextLetter) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            processCharacter(result, ch, capitalizeNextLetter);
            capitalizeNextLetter = shouldCapitalizeNext(ch, capitalizeNextLetter);
        }
        return result.toString();
    }

    private static void processCharacter(StringBuilder result, char ch, boolean capitalizeNextLetter) {
        if ('a' <= ch && ch <= 'z') {
            result.append(capitalizeNextLetter ? (char) (ch + ('A' - 'a')) : ch);
        } else if ('A' <= ch && ch <= 'Z') {
            result.append(capitalizeNextLetter ? (char) (ch + ('a' - 'A')) : ch);
        } else if ('0' <= ch && ch <= '9') {
            result.append(ch);
        }
    }

    private static boolean shouldCapitalizeNext(char ch, boolean capitalizeNextLetter) {
        return !(Character.isLetterOrDigit(ch));
    }

    private static String classNameWithoutPackage(Descriptor descriptor, ProtoFlavor flavor) {
        return stripPackageName(descriptor.getFullName(), descriptor.getFile());
    }

    private static String classNameWithoutPackage(EnumDescriptor descriptor, ProtoFlavor flavor) {
        Descriptor messageDescriptor = descriptor.getContainingType();
        return messageDescriptor == null ? descriptor.getName() :
            classNameWithoutPackage(messageDescriptor, flavor) + '.' + descriptor.getName();
    }

    private static String stripPackageName(String fullName, FileDescriptor file) {
        return file.getPackage().isEmpty() ? fullName : fullName.substring(file.getPackage().length() + 1);
    }

    private static boolean multipleJavaFiles(FileDescriptor fd, ProtoFlavor flavor) {
        return multipleJavaFiles(fd.toProto(), flavor);
    }

    private static boolean multipleJavaFiles(FileDescriptorProto fd, ProtoFlavor flavor) {
        FileOptions options = fd.getOptions();
        if (flavor == ProtoFlavor.PROTO2) {
            return options.getJavaMultipleFiles();
        }
        throw new AssertionError();
    }

    /** Derives the outer class name based on the protobuf (.proto) file name. */
    static String getFileClassName(FileDescriptor file, ProtoFlavor flavor) {
        return getFileClassName(file.toProto(), flavor);
    }

    /** Derives the outer class name based on the protobuf (.proto) file name. */
    static String getFileClassName(FileDescriptorProto file, ProtoFlavor flavor) {
        if (flavor == ProtoFlavor.PROTO2) {
            return getFileImmutableClassName(file);
        }
        throw new AssertionError();
    }

    private static String getFileImmutableClassName(FileDescriptorProto file) {
        if (file.getOptions().hasJavaOuterClassname()) {
            return file.getOptions().getJavaOuterClassname();
        }
        String className = getFileDefaultImmutableClassName(file);
        return hasConflictingClassName(file, className) ? className + "OuterClass" : className;
    }

    private static String getFileDefaultImmutableClassName(FileDescriptorProto file) {
        String name = file.getName();
        int lastSlash = name.lastIndexOf('/');
        String basename = lastSlash < 0 ? name : name.substring(lastSlash + 1);
        return underscoresToCamelCase(stripProto(basename), true);
    }

    private static String stripProto(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot >= 0) {
            String extension = filename.substring(lastDot);
            if (".protodevel".equals(extension) || ".proto".equals(extension)) {
                return filename.substring(0, lastDot);
            }
            throw new AssertionError();
        }
        return filename;
    }

    /** Used by the other overload, descends recursively into messages. */
    private static boolean hasConflictingClassName(DescriptorProto messageDesc, String name) {
        if (name.equals(messageDesc.getName())) {
            return true;
        }
        for (EnumDescriptorProto enumDesc : messageDesc.getEnumTypeList()) {
            if (name.equals(enumDesc.getName())) {
                return true;
            }
        }
        for (DescriptorProto nestedMessageDesc : messageDesc.getNestedTypeList()) {
            if (hasConflictingClassName(nestedMessageDesc, name)) {
                return true;
            }
        }
        return false;
    }

    /** Checks whether any generated classes conflict with the given name. */
    private static boolean hasConflictingClassName(FileDescriptorProto file, String name) {
        for (EnumDescriptorProto enumDesc : file.getEnumTypeList()) {
            if (name.equals(enumDesc.getName())) {
                return true;
            }
        }
        for (ServiceDescriptorProto serviceDesc : file.getServiceList()) {
            if (name.equals(serviceDesc.getName())) {
                return true;
            }
        }
        for (DescriptorProto messageDesc : file.getMessageTypeList()) {
            if (hasConflictingClassName(messageDesc, name)) {
                return true;
            }
        }
        return false;
    }
}

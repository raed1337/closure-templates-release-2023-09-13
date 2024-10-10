
package com.google.template.soy;

import static com.google.common.base.CharMatcher.whitespace;

import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.errorprone.annotations.ForOverride;
import com.google.inject.Module;
import com.google.template.soy.msgs.SoyMsgPlugin;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.NamedOptionDef;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Getter;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/** A command line parser for soy, based on args4j. */
public final class SoyCmdLineParser extends CmdLineParser {
    static {
        CmdLineParser.registerHandler(Module.class, ModuleOptionHandler.class);
        CmdLineParser.registerHandler(Boolean.class, BooleanOptionHandler.class);
        CmdLineParser.registerHandler(boolean.class, BooleanOptionHandler.class);
        CmdLineParser.registerHandler(SoyMsgPlugin.class, MsgPluginOptionHandler.class);
        CmdLineParser.registerHandler(Path.class, PathOptionHandler.class);
    }

    private final PluginLoader pluginLoader;

    SoyCmdLineParser(PluginLoader loader) {
        super(/* bean= */ null);
        this.pluginLoader = loader;
    }

    void registerFlagsObject(Object bean) {
        if (bean == null) {
            throw new IllegalArgumentException("Bean cannot be null.");
        }
        new ClassParser().parse(bean, this);
    }

    public static final class BooleanOptionHandler extends OptionHandler<Boolean> {
        public BooleanOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Boolean> setter) {
            super(parser, option, setter);
        }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            boolean value = true;
            boolean hasParam = false;
            try {
                String nextArg = params.getParameter(0);
                if ("true".equalsIgnoreCase(nextArg) || "1".equals(nextArg)) {
                    value = true;
                    hasParam = true;
                } else if ("false".equalsIgnoreCase(nextArg) || "0".equals(nextArg)) {
                    value = false;
                    hasParam = true;
                }
            } catch (CmdLineException e) {
                // No additional args on command line. No param means set flag to true.
            }
            setter.addValue(value);
            return hasParam ? 1 : 0;
        }

        @Override
        public String getDefaultMetaVariable() {
            return null;
        }
    }

    abstract static class ListOptionHandler<T> extends OptionHandler<T> {
        ListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super T> setter) {
            super(parser, option, setter);
        }

        abstract T parseItem(String item);

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            String parameter = params.getParameter(0);
            if (!parameter.isEmpty()) {
                for (String item : parameter.split(",")) {
                    setter.addValue(parseItem(item));
                }
            }
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "ITEM,ITEM,...";
        }
    }

    public static final class StringListOptionHandler extends ListOptionHandler<String> {
        public StringListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super String> setter) {
            super(parser, option, setter);
        }

        @Override
        String parseItem(String item) {
            return item;
        }
    }

    public static final class ModuleListOptionHandler extends ListOptionHandler<Module> {
        public ModuleListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Module> setter) {
            super(parser, option, setter);
        }

        @Override
        Module parseItem(String item) {
            return instantiateObject(
                ((NamedOptionDef) option).name(),
                "plugin module",
                Module.class,
                ((SoyCmdLineParser) this.owner).pluginLoader,
                item);
        }
    }

    public static final class SourceFunctionListOptionHandler extends ListMultimapOptionHandler<String, SoySourceFunction> {
        public SourceFunctionListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super ListMultimap<String, SoySourceFunction>> setter) {
            super(parser, option, setter);
        }

        @Override
        protected String parseKey(String key) {
            return key;
        }

        @Override
        protected SoySourceFunction parseValue(String value) {
            return instantiateObject(
                ((NamedOptionDef) option).name(),
                "plugin SoySourceFunction",
                SoySourceFunction.class,
                ((SoyCmdLineParser) this.owner).pluginLoader,
                value);
        }
    }

    public static final class FileListOptionHandler extends ListOptionHandler<File> {
        public FileListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super File> setter) {
            super(parser, option, setter);
        }

        @Override
        File parseItem(String item) {
            return new File(item);
        }
    }

    public static final class PathListOptionHandler extends ListOptionHandler<Path> {
        public PathListOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Path> setter) {
            super(parser, option, setter);
        }

        @Override
        Path parseItem(String item) {
            return Paths.get(item);
        }
    }

    public static final class PathOptionHandler extends OptionHandler<Path> {
        public PathOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Path> setter) {
            super(parser, option, setter);
        }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            String parameter = params.getParameter(0);
            setter.addValue(parameter.isEmpty() ? null : Paths.get(parameter));
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "foo/bar/baz";
        }
    }

    public static final class ModuleOptionHandler extends OptionHandler<Module> {
        public ModuleOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Module> setter) {
            super(parser, option, setter);
        }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            String parameter = params.getParameter(0);
            setter.addValue(parameter.isEmpty() ? null : instantiateObject(
                ((NamedOptionDef) option).name(),
                "plugin module",
                Module.class,
                ((SoyCmdLineParser) this.owner).pluginLoader,
                parameter));
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "com.foo.bar.BazModule";
        }
    }

    public static final class MsgPluginOptionHandler extends OptionHandler<SoyMsgPlugin> {
        public MsgPluginOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super SoyMsgPlugin> setter) {
            super(parser, option, setter);
        }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            String parameter = params.getParameter(0);
            setter.addValue(parameter.isEmpty() ? null : instantiateObject(
                ((NamedOptionDef) option).name(),
                "msg plugin",
                SoyMsgPlugin.class,
                ((SoyCmdLineParser) this.owner).pluginLoader,
                parameter));
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "com.foo.bar.BazModule";
        }
    }

    abstract static class MultimapOptionHandler<T extends Multimap<K, V>, K, V> extends OptionHandler<T> {
        protected MultimapOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super T> setter) {
            super(parser, option, setter);
        }

        protected abstract T emptyMultimap();

        @ForOverride
        protected abstract K parseKey(String key);

        @ForOverride
        protected abstract V parseValue(String value);

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            T builder = getExistingBuilder();
            String parameter = params.getParameter(0);
            Splitter valueSplitter = Splitter.on(",").omitEmptyStrings();
            for (String s : Splitter.on(";").omitEmptyStrings().split(parameter)) {
                int index = s.indexOf("=");
                if (index == -1) {
                    throw new CommandLineError("Invalid multimap flag entry. No '=' found: " + s);
                } else {
                    K key = parseKey(whitespace().trimFrom(s.substring(0, index)));
                    String allValStr = whitespace().trimFrom(s.substring(index + 1));
                    for (String valStr : valueSplitter.split(allValStr)) {
                        builder.put(key, parseValue(valStr));
                    }
                }
            }
            setter.addValue(builder);
            return 1;
        }

        private T getExistingBuilder() {
            if (setter instanceof Getter) {
                @SuppressWarnings("unchecked")
                Getter<T> getter = (Getter<T>) setter;
                if (!getter.getValueList().isEmpty()) {
                    return getter.getValueList().get(0);
                }
            }
            return emptyMultimap();
        }

        @Override
        public String getDefaultMetaVariable() {
            return "KEY=VALUE1,VALUE2;KEY2=VALUE3,VALUE4;...";
        }
    }

    abstract static class ListMultimapOptionHandler<K, V> extends MultimapOptionHandler<ListMultimap<K, V>, K, V> {
        ListMultimapOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super ListMultimap<K, V>> setter) {
            super(parser, option, setter);
        }

        @Override
        protected ListMultimap<K, V> emptyMultimap() {
            return ArrayListMultimap.create();
        }
    }

    public static final class StringStringMapHandler extends MapHandler<String, String> {
        public StringStringMapHandler(CmdLineParser parser, OptionDef option, Setter<? super Map<String, String>> setter) {
            super(parser, option, setter);
        }

        @Override
        protected String parseKey(String key) {
            return key;
        }

        @Override
        protected String parseValue(String value) {
            return value;
        }
    }

    abstract static class MapHandler<K, V> extends OptionHandler<Map<K, V>> {
        MapHandler(CmdLineParser parser, OptionDef option, Setter<? super Map<K, V>> setter) {
            super(parser, option, setter);
        }

        @ForOverride
        protected abstract K parseKey(String key);

        @ForOverride
        protected abstract V parseValue(String value);

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
            String parameters = params.getParameter(0);
            for (String parameter : Splitter.on(",").split(parameters)) {
                int index = parameter.indexOf("=");
                if (index == -1) {
                    throw new CommandLineError("Invalid map flag entry. No '=' found: " + parameter);
                } else {
                    K key = parseKey(whitespace().trimFrom(parameter.substring(0, index)));
                    V val = parseValue(whitespace().trimFrom(parameter.substring(index + 1)));
                    builder.put(key, val);
                }
            }
            setter.addValue(builder.buildOrThrow());
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "KEY=VALUE1,KEY2=VALUE3,VALUE4;...";
        }
    }

    private static <T> T instantiateObject(String flagName, String objectType, Class<T> clazz, PluginLoader loader, String instanceClassName) {
        try {
            return loader.loadPlugin(instanceClassName).asSubclass(clazz).getConstructor().newInstance();
        } catch (ClassCastException cce) {
            throw new CommandLineError(String.format("%s \"%s\" is not a subclass of %s. Classes passed to %s should be %ss. Did you pass it to the wrong flag?", objectType, instanceClassName, clazz.getSimpleName(), flagName, clazz.getSimpleName()), cce);
        } catch (ReflectiveOperationException e) {
            throw new CommandLineError(String.format("Cannot instantiate %s \"%s\" registered with flag %s. Please make sure that the %s exists and is on the compiler classpath and has a public zero arguments constructor.", objectType, instanceClassName, flagName, objectType), e);
        } catch (ExceptionInInitializerError e) {
            throw new CommandLineError(String.format("Cannot instantiate %s \"%s\" registered with flag %s. An error was thrown while loading the class. There is a bug in the implementation.", objectType, instanceClassName, flagName), e);
        } catch (SecurityException e) {
            throw new CommandLineError(String.format("Cannot instantiate %s \"%s\" registered with flag %s. A security manager is preventing instantiation.", objectType, instanceClassName, flagName), e);
        }
    }
}

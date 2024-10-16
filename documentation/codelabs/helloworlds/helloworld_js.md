# Hello World Using JavaScript

## Hello World

Follow these steps to create a simple Hello World template and use it in
JavaScript:

Download
[`closure-templates-for-javascript-latest.zip`](https://dl.google.com/closure-templates/closure-templates-for-javascript-latest.zip).

This archive contains the following files:

-   `SoyToJsSrcCompiler.jar` — A standalone executable jar file that compiles
    Soy into efficient JavaScript functions.
-   `soyutils.js` — A library of helper utilities. All JavaScript code that's
    generated by the template compiler use these utilities. These depend on
    Closure Library.

Put these files in your working directory, for example in:

    ~/helloworld_js/

All files that contain Soy templates end with the `.soy` file extension and are
called Soy files. Create a file in your working directory called `simple.soy`
and copy this line to the file:

```soy
{namespace examples.simple}
```

This line declares a namespace for all the templates that you define in this
file.

Copy the following basic template to the file, making sure that it appears
**after** the namespace declaration:

```soy
/**
 * Says hello to the world.
 */
{template helloWorld}
  Hello world!
{/template}
```

This template simply outputs the text "`Hello world!`". The template's partial
name is `helloWorld`, which, when combined with the namespace, forms the fully
qualified template name `examples.simple.helloWorld`.

To run the Soy compiler for turning your templates into JavaScript code, you'll
need to have Java Runtime Environment (JRE) version 6 installed, with the
executable `java` on your path. Compile the template file using the following
command:

    ~/helloworld_js$ java -jar SoyToJsSrcCompiler.jar --outputPathFormat simple.js --srcs simple.soy

This command generates a JavaScript file called `simple.js`, which includes a
function `examples.simple.helloWorld()` for rendering the `helloWorld` template.

Create a file in `~/helloworld_js/` called `helloworld.html` and copy the HTML
below into the file:

```html
<html>
<head>
  <title>Hello World</title>
  <script type="text/javascript" src="soyutils.js"></script>
  <script type="text/javascript" src="simple.js"></script>
</head>
<body>
  <script type="text/javascript">
    // Exercise the .helloWorld template
    document.write(examples.simple.helloWorld());
  </script>
</body>
</html>
```

This simple page includes a JavaScript call to render the Hello World template.

Navigate to the page `~/helloworld_js/helloworld.html` in your browser to see
the rendered template. The page should look like the screenshot below:

![Screenshot of Hello World example](../images/helloworld_js.png)

That's it! You've just worked through an example of creating the most basic
Closure Template and using it from JavaScript. In the next section you will
build on this example by adding two other simple templates to `simple.soy` and
using them from JavaScript.

## Hello Name and Hello Names

Add the following second template, called `helloName`, to `simple.soy`. Note
that `helloName` takes a required parameter called `name`, which is declared by
`@param`. It also takes an optional parameter `greetingWord`, which is declared
by `@param?`. These parameters are referenced in the template body using the
expressions `$name` and `$greetingWord`, respectively. This template also
demonstrates that you can conditionally include content in templates via the
`if-else` commands. You can put this template before or after the `helloWorld`
template, just as long as it's after the `namespace` declaration.

```soy
/**
 * Greets a person using "Hello" by default.
 * @param name The name of the person.
 * @param? greetingWord Optional greeting word to use instead of "Hello".
 */
{template helloName}
  {if not $greetingWord}
    Hello {$name}!
  {else}
    {$greetingWord} {$name}!
  {/if}
{/template}
```

Recompile the template file using the same command that you ran in the Hello
World example:

    ~/helloworld_js$ java -jar SoyToJsSrcCompiler.jar --outputPathFormat simple.js --srcs simple.soy

Take a closer look at the output of this compilation step. Open the output file
`~/helloworld_js/simple.js`, which contains the following generated code:

```java
// This file was automatically generated from simple.soy.
// Please don't edit this file by hand.

/**
 * @fileoverview Templates in namespace examples.simple.
 */

if (typeof examples == 'undefined') { var examples = {}; }
if (typeof examples.simple == 'undefined') { examples.simple = {}; }


examples.simple.helloWorld = function(opt_data, opt_ignored) {
  return 'Hello world!';
};
if (goog.DEBUG) {
  examples.simple.helloWorld.soyTemplateName = 'examples.simple.helloWorld';
}


examples.simple.helloName = function(opt_data, opt_ignored) {
  return '' + ((! opt_data.greetingWord) ? 'Hello ' + soy.$$escapeHtml(opt_data.name) + '!' : soy.$$escapeHtml(opt_data.greetingWord) + ' ' + soy.$$escapeHtml(opt_data.name) + '!');
};
if (goog.DEBUG) {
  examples.simple.helloName.soyTemplateName = 'examples.simple.helloName';
}
```

This file was generated by the template compiler and contains a JavaScript
function for each of the templates in `simple.soy`. In this case it contains the
functions `examples.simple.helloWorld()` and `examples.simple.helloName()`.

Add a third template to the file. This template, `helloNames`, demonstrates a
`for` loop. It also shows how to call other templates and insert their output
using the `call` command. Note that the `data="all"` attribute in the `call`
command passes all of the caller's template data to the callee template.

```soy
/**
 * Greets a person and optionally a list of other people.
 * @param name The name of the person.
 * @param additionalNames The additional names to greet. May be an empty list.
 */
{template helloNames}
  // Greet the person.
  {call helloName data="all" /}<br>
  // Greet the additional people.
  {for $additionalName, $i in $additionalNames}
    {if $i > 0}
      <br>  // break after every line except the last
    {/if}
    {call helloName}
      {param name: $additionalName /}
    {/call}
  {/for}
{/template}
```

Recompile to pick up the new template:

    ~/helloworld_js$ java -jar SoyToJsSrcCompiler.jar --outputPathFormat simple.js --srcs simple.soy

Edit `helloworld.html` and add the bolded lines below to exercise the
`helloName` and `helloNames` templates with data:

```html
<html>
<head>
  <title>Hello World</title>
  <script type="text/javascript" src="soyutils.js"></script>
  <script type="text/javascript" src="simple.js"></script>
</head>
<body>
  <script type="text/javascript">
    // Exercise the .helloWorld template
    document.write(examples.simple.helloWorld());
    // Exercise the .helloName template
    document.write('<hr>' + examples.simple.helloName({name: 'Ana'}));
    // Exercise the .helloNames template
    document.write('<hr>' + examples.simple.helloNames(
        {name: 'Ana', additionalNames: ['Bob', 'Cid', 'Dee']}));
  </script>
</body>
</html>
```

Reload the page `~/helloworld_js/helloworld.html` to see the updated result. The
page should now look like the screenshot below:

![Screenshot of more advanced Hello World
example](../images/helloworld_js_advanced.png)

You've just completed the exercise of creating three simple Soy templates and
using them in JavaScript. Where should you go next?

-   To use the same templates from this chapter in Java, try the
    [Hello World Using Java](helloworld_java.md) examples.
-   To read more about Soy concepts, take a look at the [Concepts][concepts]
    chapter.

[concepts]: /documentation/concepts/index.md

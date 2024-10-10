# Let command

## let {#let}

Syntax for arbitrary values:

```soy
{let $<IDENTIFIER>: <EXPRESSION> /}
```

Syntax for rendering to string ("block form"):

```soy
{let $<IDENTIFIER> kind="IDENTIFIER_TYPE"}...{/let}
```

`let` defines a name for an intermediate immutable value. The name is defined
only within the immediate code block containing the `let` command. As these
values are immutable there is no syntax for updating their values and defining
multiple `let` values with the same name is a compilation error.

The `kind` attribute specifies the
[content kind](../dev/security.md#content_kinds), but can be omitted if the
`let` contains a single call, in which case the `kind` is inferred from the
called template.

```soy
{let $<IDENTIFIER>}{call someTemplate /}{/let}
```

You might use `let` because you need to reuse the intermediate value multiple
times, or you need to print a rendered value using a directive, or you feel it
improves the readability of your code.

Example:

```soy
{let $isEnabled: $isAaa and not $isBbb and $ccc == $ddd + $eee /}
{if $isEnabled and $isXxx}
  ...
{elseif not $isEnabled and $isYyy}
  ...
{/if}
```

The second syntax form listed above renders the contents of the `let` block to a
string, including applying autoescaping. It is sometimes needed, but should be
used sparingly.

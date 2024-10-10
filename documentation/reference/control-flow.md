# Control Flow

[TOC]

## if, elseif, else {#if}

Syntax:

```soy
{if <expression>}
  ...
{elseif <expression>}
  ...
{else}
  ...
{/if}
```

Use these commands for conditional output. It works exactly like you'd expect!

As soon one expression evaluates to true, the compiler processes that sub-block
and skips the rest. The `else` sub block is evaluated when none of the `if` and
`elseif` conditions are true.

For example:

```soy
{if round($pi, 2) == 3.14}
  {$pi} is a good approximation of pi.
{elseif round($pi) == 3}
  {$pi} is a bad approximation of pi.
{else}
  {$pi} is nowhere near the value of pi.
{/if}
```

Example output (for pi = 2.71828):

```
2.71828 is a bad approximation of pi.
```

## switch, case, default {#switch}

Syntax:

```soy
{switch <expression>}
  {case <expression_list>}
    ...
  {case <expression_list>}
    ...
  {default}
    ...
{/switch}
```

The `switch` command is used for conditional output based on the value of an
expression, and works how you'd expect.

Each `case` can have one or more expressions (use a comma-separated list if you
have more than one), and the `case` matches when any one of its expressions
matches the value of the `switch` expression. `{default}` executes only when
none of the expressions from the `case` statements match the `switch`
expression. Cases with an empty body do not "fall through" as they might in Java
or JavaScript.

For example:

```soy
{switch $numMarbles}
  {case 0}
    You have no marbles.
  {case 1, 2, 3}
    You have a normal number of marbles.
  {case 4}
  {default}  // 5 or more
    You have more marbles than you know what to do with.
{/switch}
```

Example output (for `numMarbles` = 2):

```
You have a normal number of marbles.
```

For `numMarbles` = 4, the output would be empty.

## for {#for}

Syntax:

```soy
{for <local_var> in <data_ref>}
  ...
{/for}
```

The `for` command iterates over a list. The iterator `local_var` is a local
variable that is defined only in the block.

For example:

```soy
{for $operand in $operands}
  {$operand}
{/for}
```

Example output (for operands = `['alpha', 'beta', 'gamma']`):

```
alphabetagamma
```

### Indexed iteration {#for-indexed}

The `for` command also accepts an optional, zero-based **position index**, such
as:

```soy
{for $operand, $index in $operands}
  {if $index != 0} + {/if}
  {$operand}({$index})
{/for}
```

For the original `$operands` = `['alpha', 'beta', 'gamma']`, this would output:

```
alpha(0) + beta(1) + gamma(2)
```

If you only want to iterate over a list of numbers, you can use the
[`range(...)` function](functions.md#range), for example:

```soy
{for $i, $idx in range(5)}
  {if $idx > 0}, {/if}
  {$i}
{/for}
```

Example output: `0, 1, 2, 3, 4`

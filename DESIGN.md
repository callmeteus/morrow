# Design system

Read from the current implementation. Where a value appears in more than one place, the keyboard source (`packages/app/src/main/java/dev/gti/gboard/GboardIme.java`) is the reference.

## Color

Light theme:

- Keyboard background: `#F1F3F4`
- Key background: `#FFFFFF`
- Key text: `#202124`
- Special key background: `#DADCE0`
- Accent: `#1A73E8`
- Separator: `#DADCE0`

Dark theme:

- Keyboard background: `#1B1D1E`
- Key background: `#2D3031`
- Key text: `#E8EAED`
- Special key background: `#3B3F41`
- Accent: `#A8E6C5` (mint green)
- Separator: `#3C4043`

Status bar and setup window background: `#202124`. Launcher background: `#1A73E8`. Toolbar and popup surfaces use `#414249` in dark mode and `#E8EAED` in light mode. The glide preview overlay uses `#CC202124`.

The theme follows the system light or dark mode.

## Shape

- Keys and the dictation bar use rounded rectangles with a 12 dp corner radius.
- The microphone key uses a 20 dp corner radius.
- The backspace key uses a 16 dp corner radius.
- The glide trail is a rounded stroke of 13 dp with decay.

## Spacing and sizing

- Keyboard key rows are fixed at about 50 dp each; total keyboard height is about 316 dp.
- Key padding is 4 dp horizontally and 6 dp vertically.
- The suggestion strip is 44 dp tall. The dictation bar is 48 dp tall with 12 dp horizontal padding.
- The microphone key is 44 dp by 44 dp; the cancel button is 40 dp by 40 dp.

## Type and content

- System font. Key labels are single characters or short words.
- The suggestion strip shows the word the user produced first and the corrector's best candidate in the centre. Only the word that will be committed gets the heavy font weight.
- The idle strip shows a toolbar with keyboard, clipboard, GIF, emoji and settings entries.
- The dictation bar shows a live transcript and a cancel control.

## Motion and feedback

- Pressed keys show a pressed state.
- The waveform is driven by microphone RMS, not a random animation.
- A trailing ellipsis animation appears after the microphone is released.
- Glide decoding shows a floating preview above the finger.

## Accessibility and states

- Long press opens an accent popup.
- Shift supports single shift and caps lock on double tap.
- Backspace repeats after 400 ms, then every 80 ms while held.
- Swiping the space bar moves the cursor continuously.
- Long press on the space bar opens a one-handed shortcut popup.

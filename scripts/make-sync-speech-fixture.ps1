param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$sentences = @(
 'We should leave before the rain starts.',
 'The train arrives at the station tomorrow morning.',
 'Can you hear the music from the next room?',
 'I have been waiting here for a very long time.',
 'Please put the book back on the table.',
 'There is something I need to tell you about the house.',
 'Everything will be ready by the end of the week.',
 'She opened the window and looked outside.',
 'We found the missing keys near the garden gate.',
 'Do you remember where we parked the car?',
 'It was a beautiful day for a walk along the river.',
 'Let us finish this conversation after dinner.'
)
$voice = New-Object -ComObject SAPI.SpVoice
for ($i = 0; $i -lt $sentences.Count; $i++) {
 $stream = New-Object -ComObject SAPI.SpFileStream
 $stream.Open((Join-Path $OutputDirectory "voice-$i.wav"), 3, $false)
 $voice.AudioOutputStream = $stream
 [void]$voice.Speak($sentences[$i])
 $stream.Close()
}

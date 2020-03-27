@echo off

mkdir public\compiled

set ts_apps=common draughts ceval game chat draughtsground tree nvui

call yarn install

for %%t in (%ts_apps%) do @(
    call echo Building TypeScript: %%t
    call cd ui\%%t
    call yarn run compile
    call cd ..\..
)

call echo Building: draughtsground stand alone
call cd ui\draughtsground
call gulp dev
call gulp prod
call cd ..\..
call xcopy /y public\compiled\draughtsground.min.js public\javascripts\vendor\

set apps=site challenge notify round analyse editor puzzle lobby tournament tournamentSchedule tournamentCalendar simul perfStat dasher cli

for %%a in (%apps%) do @(
  call echo Building: %%a
  call cd ui\%%a
  call gulp dev
  call cd ..\..
)

call echo Building: editor.min
call cd ui\editor
call gulp prod
call cd ..\..

set css_apps=site challenge notify dasher tournament
for %%a in (%css_apps%) do @(
  call echo Building css: %%a
  call cd ui\%%a
  call gulp css-dev
  call cd ..\..
)
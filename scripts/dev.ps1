wt -w 0 `
  new-tab powershell -NoExit -Command "title JAVA-API; nx serve platform" `
  ; split-pane -H powershell -NoExit -Command "title ANALYTICS; nx serve analytics"
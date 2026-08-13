; One deliberate input into the Minecraft window. Verbs, not a script -- the caller screenshots
; between steps and decides what is safe to send next.
;
;   mckey.ahk key {Escape}      send a single key
;   mckey.ahk text "/voxymap"   type literal text, no Enter
;   mckey.ahk enter             press Enter
;
; Why it is split up: Minecraft auto-pauses when the window loses focus, so a blind sequence can
; land on the Game Menu -- where the highlighted button is Disconnect and Enter would press it.
; Nothing here presses Enter unless the caller has looked at a screenshot first.
;
; Send (SendInput), not ControlSend: the game reads the keyboard through GLFW, which only sees real
; input-queue events.
#Requires AutoHotkey v2.0
#SingleInstance Force
SetTitleMatchMode 2
SetKeyDelay 40, 40

if (A_Args.Length < 1) {
    FileAppend "usage: mckey.ahk key|text|enter [arg]`n", "*"
    ExitApp 1
}
verb := A_Args[1]

if !WinExist("Minecraft") {
    FileAppend "no Minecraft window`n", "*"
    ExitApp 2
}

WinActivate
if !WinWaitActive("Minecraft", , 5) {
    FileAppend "could not activate the window`n", "*"
    ExitApp 3
}
Sleep 500

switch verb {
    case "key":
        if (A_Args.Length < 2) {
            FileAppend "key needs an argument`n", "*"
            ExitApp 1
        }
        Send A_Args[2]
        FileAppend "key " A_Args[2] "`n", "*"
    case "text":
        if (A_Args.Length < 2) {
            FileAppend "text needs an argument`n", "*"
            ExitApp 1
        }
        SendText A_Args[2]
        FileAppend "text " A_Args[2] "`n", "*"
    case "look":
        ; Relative mouse motion via mouse_event(MOUSEEVENTF_MOVE). options.txt has
        ; rawMouseInput:true, so the game reads WM_INPUT deltas -- SetCursorPos would move the
        ; pointer without producing any, and the view would not turn at all.
        ;
        ; Fed in small steps because the camera integrates per frame; one huge delta lands as a
        ; single snap and overshoots on a frame drop.
        ;
        ; At mouseSensitivity 0.5 Minecraft turns 0.15 degrees per unit, so ~600 units is a right
        ; angle. Positive dx looks right, positive dy looks down.
        if (A_Args.Length < 3) {
            FileAppend "look needs dx and dy`n", "*"
            ExitApp 1
        }
        dx := Integer(A_Args[2])
        dy := Integer(A_Args[3])
        steps := 24
        Loop steps {
            DllCall("mouse_event", "UInt", 0x0001, "Int", dx // steps, "Int", dy // steps, "UInt", 0, "UPtr", 0)
            Sleep 12
        }
        FileAppend "look " dx "," dy "`n", "*"
    case "hold":
        ; Hold a movement key. NOTE: no mouse buttons anywhere in this file -- left and right click
        ; are attack and use, and this is someone's live survival world.
        if (A_Args.Length < 3) {
            FileAppend "hold needs a key and a duration`n", "*"
            ExitApp 1
        }
        key := A_Args[2]
        Send "{" key " down}"
        Sleep Integer(A_Args[3])
        Send "{" key " up}"
        FileAppend "held " key " for " A_Args[3] "ms`n", "*"
    case "runcmd":
        ; Open chat, type, send -- one invocation. Safe now that pauseOnLostFocus is off: the game
        ; no longer throws up the Game Menu when focus moves, so Enter can only ever land in the
        ; chat box. Before that change this sequence could press Disconnect.
        if (A_Args.Length < 2) {
            FileAppend "runcmd needs an argument`n", "*"
            ExitApp 1
        }
        Send "t"
        Sleep 700
        SendText A_Args[2]
        Sleep 400
        Send "{Enter}"
        FileAppend "ran: " A_Args[2] "`n", "*"
    case "resumechat":
        ; The game is on the Game Menu every time we arrive, because pauseOnLostFocus is on and
        ; the previous invocation exiting is a focus loss. So: dismiss the menu, THEN open chat,
        ; without ever letting go of focus in between. Escape from the Game Menu returns to the
        ; world; `t` on the Game Menu does nothing at all, which is why every split-up attempt
        ; typed into the void.
        if (A_Args.Length < 2) {
            FileAppend "resumechat needs an argument`n", "*"
            ExitApp 1
        }
        Send "{Escape}"
        Sleep 900
        Send "t"
        Sleep 900
        SendText A_Args[2]
        FileAppend "resumechat typed (not sent): " A_Args[2] "`n", "*"
    case "chat":
        ; Opening chat and typing must be one invocation. Between two separate ones the game
        ; regains and loses focus, and with pauseOnLostFocus it puts the Game Menu up in the gap --
        ; which is how a stray keystroke ends up on the Disconnect button. Once the chat screen is
        ; open the pause menu no longer appears on focus loss, so Enter can safely be its own call
        ; after the caller has looked at a screenshot.
        if (A_Args.Length < 2) {
            FileAppend "chat needs an argument`n", "*"
            ExitApp 1
        }
        Send "t"
        Sleep 900
        SendText A_Args[2]
        FileAppend "chat typed (not sent): " A_Args[2] "`n", "*"
    case "enter":
        Send "{Enter}"
        FileAppend "enter`n", "*"
    default:
        FileAppend "unknown verb: " verb "`n", "*"
        ExitApp 1
}

Sleep 400
ExitApp 0

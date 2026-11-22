// Primeiro import do app: timers corretos antes do polyfill do modelo oficial (ver utils/timers.js).
import { installTimers } from "./utils/timers";
import { getGlobal } from "./shared/global";

installTimers(getGlobal());

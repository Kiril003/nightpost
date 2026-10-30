"""
Action registry — name → Action class.

Central catalog so the planner can introspect available capabilities and the
executor can construct the right action from an LLM-supplied (name, args).
"""
from __future__ import annotations

from typing import Type

from pydantic import ValidationError
from pydantic_core import PydanticUndefined

from ..schemas import RiskLevel
from .ask_user import AskUser
from .base import Action
from .bash import BashRun
from .browser import BrowserClickByDescription, BrowserExtract, BrowserNavigate
from .device import (
    ATSPIClickByLabel, ATSPIFindByLabel,
    BlenderRun,
    GameInputBurst, ScreenCapture, ScreenClick,
    ScreenKeyCombo, ScreenOCR, ScreenScroll, ScreenType,
)
from .fs import FsRead, FsWrite, FsBackup, FsCodeSearch, FsPatchHash
from .lsp import LspDiagnostics, LspGotoDefinition, LspGetSymbols
from .net import NetScan
from .notify import NotifyDesktop
from .process import ProcessList
from .research import WebResearch
from .self_introspect import SelfCapability, SelfRecall
from .time_ import TimeWait
from .voice_listen import VoiceListen
from .voice_say import VoiceSay
from .vision import SeeCamera, SeeScreen
from .visual import (
    VisualClickTarget, VisualFindTarget, VisualSceneDescribe, VisualWaitFor,
)
from .delegate import AgentDelegate
from .team_assemble import AgentAssembleTeam
from .map import MAP_ACTIONS
from .messenger import MESSENGER_ACTIONS
from .body import BODY_ACTIONS
from .pc import PC_ACTIONS
from .web import WebSearch
from .git_checkpoint import GitCheckpoint, GitRollback
from .git_branch import GitBranchManage
from .mission_assets import MissionSnapshot
from .grounded import TestRun
from .proof_dev_task import ProofDevTask
from .intelligence import DistillFactsFromChats, ExcludeFactFromPrompts, IntelligenceUpsertKnowledge
from .will_seed import WillSeedGoal
from .will_status import WillStatus

_REGISTERED: list[Type[Action]] = [
    # Sub-project A — Will Engine operator surface.
    WillSeedGoal, WillStatus,
    FsRead, FsWrite, FsBackup, FsCodeSearch, FsPatchHash,
    LspDiagnostics, LspGotoDefinition, LspGetSymbols,
    BashRun,
    BrowserNavigate, BrowserExtract, BrowserClickByDescription,
    NetScan,
    ProcessList,
    NotifyDesktop,
    TimeWait,
    SelfCapability, SelfRecall,
    WebSearch,
    GitCheckpoint, GitRollback, GitBranchManage,
    # Phase 17a.5 — typed prompt to the operator.
    AskUser,
    # Phase 17a.5 — iterative corroboration search.
    WebResearch,
    # Phase 18 — desktop / screen actor control.
    ScreenCapture, ScreenOCR,
    ScreenClick, ScreenType, ScreenKeyCombo, ScreenScroll,
    # Phase 18 — long-running headless apps + high-frequency game input.
    BlenderRun, GameInputBurst,
    # Phase 18-COMPLETE — semantic UI targeting via AT-SPI.
    ATSPIFindByLabel, ATSPIClickByLabel,
    # Phase 23-A — agent-initiated voice. `voice.say` lets a step emit
    # synthesized speech (gated on GHOST/SHADOW unless force=True);
    # `voice.listen` blocks until the next user transcript arrives via
    # the always-on pipeline event_bus republisher.
    VoiceSay, VoiceListen,
    # Phase 23-B — agent-initiated vision via Gemini multimodal.
    SeeScreen,
    # Day-NN — physical camera vision via OpenCV + Gemini multimodal.
    # Closes the audit-flagged Level-4 gap: agent can finally LOOK at
    # the room, not just the desktop. Same JSON shape as SeeScreen.
    SeeCamera,
    # Phase 24-V — visual acting layer. Composes screen capture +
    # OmniParser grounding + desktop_control into description-based
    # find / click / wait / scene-describe so the planner can name UI
    # in natural language without ever reasoning about pixel coords.
    VisualFindTarget, VisualClickTarget, VisualWaitFor, VisualSceneDescribe,
    # Phase 24-B — `map.*` agent action surface. 12 verbs, every
    # mutation broadcast on the `"map"` WS channel via
    # `agent.actions.map._common.broadcast_map_mutation`.
    *MAP_ACTIONS,
    # Симбіот, крок 4 — месенджер і тіла як типізовані інструменти розуму:
    # читати/шукати/писати від імені власника (лист — з «так» людини, перший
    # лист новій людині — HIGH), дієслова тіл зі спільного контракту.
    *MESSENGER_ACTIONS,
    *BODY_ACTIONS,
    # Тіло ПК: бачити (екран, вікна, файли, буфер, процеси, браузер) і діяти
    # (програми, клавіші, миша, файли з версіями, оболонка в пісочниці,
    # браузер PHANTOM) — через драбину розуму на ПК і стрічку «Що я роблю».
    *PC_ACTIONS,
    # Phase 26-A — agent.delegate primitive. Lets the planner spawn
    # specialist sub-agents (senior_backend, reviewer, researcher,
    # etc.), await their TaskReports, and merge results back as
    # Observations. The mechanism that turns the single-loop agent
    # into a real team. Recursion + concurrency capped via
    # agent_max_delegation_depth + agent_max_team_concurrency.
    AgentDelegate,
    # Phase 26-C — agent.assemble_team. High-level "delegate to a
    # whole team" verb that bundles picker + delegate + parallel
    # await + result consolidation. Three modes: auto (picker), department
    # (spawns matching team_lead which re-delegates to its seniors),
    # explicit (caller names roles).
    AgentAssembleTeam,
    # Vertical V10 — mission.snapshot. Captures a visual PNG from screen /
    # camera / blender / file, optionally describes it via Gemini multimodal,
    # stores it in the mission asset dir, and appends an image directive to
    # the active ledger phase section.
    MissionSnapshot,
    TestRun,
    # Зріз доказу: задача розробки -> контракт-тест червоний-до/зелений-після.
    ProofDevTask,
    # Vertical V11 — intelligence.distill_from_chats + intelligence.exclude_fact.
    # Distillation scans recent chat history via Gemini and proposes new personal
    # facts; exclude_fact toggles the exclude_from_prompts gate on a UserFact.
    DistillFactsFromChats,
    ExcludeFactFromPrompts,
    IntelligenceUpsertKnowledge,
]


class ActionRegistry:
    def __init__(self) -> None:
        self._by_name: dict[str, Type[Action]] = {cls.name: cls for cls in _REGISTERED}

    def all(self) -> list[Type[Action]]:
        return list(self._by_name.values())

    def names(self) -> list[str]:
        return list(self._by_name.keys())

    def get(self, name: str) -> Type[Action] | None:
        return self._by_name.get(name)

    def build(self, name: str, args: dict) -> Action:
        cls = self.get(name)
        if cls is None:
            raise KeyError(f"Unknown action: {name}")
        try:
            return cls(**args)
        except ValidationError as exc:
            raise ValueError(f"bad_args for {name}: {exc.errors()}") from exc

    def catalog(self) -> list[dict]:
        """Compact catalog for planner prompt — name + risk + arg signatures."""
        out = []
        for cls in self._by_name.values():
            fields = {}
            for fname, finfo in cls.model_fields.items():
                # Pull a stable representation of the type
                anno = finfo.annotation
                ann_str = getattr(anno, "__name__", None) or str(anno)
                default = finfo.default
                if default is PydanticUndefined or default is ...:
                    default = None
                fields[fname] = {
                    "type": ann_str,
                    "default": default,
                    "required": finfo.is_required(),
                    "description": finfo.description or "",
                }
            out.append({
                "name": cls.name,
                "risk_level": int(cls.risk_level),
                "risk_label": RiskLevel(cls.risk_level).name,
                "args": fields,
                "reversible": bool(cls.reversible),
            })
        return out


registry = ActionRegistry()

"""messenger own self

Revision ID: c7d2e9f1a483
Revises: b5e0c7a2d914
Create Date: 2026-09-27

Особа телефона на ПК, запечатана ключем вузла (docs/ONE_SELF.md, крок 1).

Лише create_table — існуючих таблиць ця ревізія не чіпає (див. a3f6c1d95e02).
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c7d2e9f1a483'
down_revision: Union[str, None] = 'b5e0c7a2d914'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'messenger_own_self',
        sa.Column('owner_user_id', sa.String(length=36), nullable=False),
        sa.Column('self_id', sa.String(length=16), nullable=False),
        sa.Column('ed25519_pub', sa.String(length=64), nullable=False),
        sa.Column('x25519_pub', sa.String(length=64), nullable=False),
        sa.Column('sealed_keys', sa.Text(), nullable=False),
        sa.Column('handed_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['owner_user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('owner_user_id'),
    )


def downgrade() -> None:
    op.drop_table('messenger_own_self')

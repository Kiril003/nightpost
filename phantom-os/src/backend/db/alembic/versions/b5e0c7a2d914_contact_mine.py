"""contact mine

Revision ID: b5e0c7a2d914
Revises: a3f6c1d95e02
Create Date: 2026-09-27

Телефон, спарений із цим ПК, — мій пристрій, а не чужа людина. Позначку
ставить лише успішне спарювання.

Голий add_column, без batch_alter_table: batch на SQLite з FK робить DROP, а
той каскадом зносить розмови й листи (див. a3f6c1d95e02).
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b5e0c7a2d914'
down_revision: Union[str, None] = 'a3f6c1d95e02'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'messenger_contacts',
        sa.Column('mine', sa.Boolean(), nullable=False, server_default=sa.false()),
    )


def downgrade() -> None:
    op.drop_column('messenger_contacts', 'mine')

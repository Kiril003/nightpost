"""messenger phone peers

Revision ID: a3f6c1d95e02
Revises: e2a91c7f4b60
Create Date: 2026-09-25

Телефон як співрозмовник ПК: ключі з його PH2 і листи, що перетнули сховок.

Лише create_table — жодного batch_alter_table (див. e2a91c7f4b60: batch на
SQLite з FK робить DROP, а той каскадом зносить дочірні рядки). Існуючих
таблиць ця ревізія не чіпає взагалі.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'a3f6c1d95e02'
down_revision: Union[str, None] = 'e2a91c7f4b60'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'messenger_phone_peers',
        sa.Column('contact_id', sa.String(length=36), nullable=False),
        sa.Column('owner_user_id', sa.String(length=36), nullable=False),
        sa.Column('peer_id', sa.String(length=16), nullable=False),
        sa.Column('ed25519_pub', sa.String(length=64), nullable=False),
        sa.Column('x25519_pub', sa.String(length=64), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['contact_id'], ['messenger_contacts.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['owner_user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('contact_id'),
        sa.UniqueConstraint('owner_user_id', 'peer_id', name='uq_messenger_phone_peer'),
    )
    op.create_index(
        'ix_messenger_phone_peers_owner_user_id', 'messenger_phone_peers', ['owner_user_id']
    )
    op.create_table(
        'messenger_phone_letters',
        sa.Column('id', sa.String(length=36), nullable=False),
        sa.Column('contact_id', sa.String(length=36), nullable=False),
        sa.Column('direction', sa.String(length=3), nullable=False),
        sa.Column('letter_id', sa.String(length=64), nullable=False),
        sa.Column('kind', sa.String(length=12), nullable=False),
        sa.Column('message_id', sa.String(length=36), nullable=True),
        sa.Column('frame', sa.Text(), nullable=True),
        sa.Column('relay_state', sa.String(length=8), nullable=False),
        sa.Column('relay_nonce', sa.String(length=24), nullable=True),
        sa.Column('held_until', sa.DateTime(), nullable=True),
        sa.Column('attempts', sa.Integer(), nullable=False),
        sa.Column('last_error', sa.String(length=200), nullable=True),
        sa.Column('target_letter_id', sa.String(length=64), nullable=True),
        sa.Column('emoji', sa.String(length=64), nullable=True),
        sa.Column('mark_on', sa.Boolean(), nullable=True),
        sa.Column('sent_at_ms', sa.BigInteger(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['contact_id'], ['messenger_contacts.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['message_id'], ['messenger_messages.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint(
            'contact_id', 'direction', 'letter_id', name='uq_messenger_phone_letter'
        ),
    )
    op.create_index(
        'ix_messenger_phone_letters_contact_id', 'messenger_phone_letters', ['contact_id']
    )
    op.create_index(
        'ix_messenger_phone_letters_message_id', 'messenger_phone_letters', ['message_id']
    )
    op.create_index(
        'ix_messenger_phone_letters_relay', 'messenger_phone_letters', ['direction', 'relay_state']
    )
    op.create_index(
        'ix_messenger_phone_letters_target',
        'messenger_phone_letters',
        ['contact_id', 'target_letter_id'],
    )


def downgrade() -> None:
    op.drop_table('messenger_phone_letters')
    op.drop_table('messenger_phone_peers')

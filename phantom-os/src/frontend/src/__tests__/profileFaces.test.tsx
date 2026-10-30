import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ProfilesHost } from '../components/messenger/ProfilesSheet';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { resolveFace, withFaceField } from '../services/profileFace';

function openFaces() {
  render(<ProfilesHost />);
  fireEvent.click(screen.getByTestId('me-profiles-open'));
  fireEvent.click(screen.getByTestId('profile-add-open'));
  fireEvent.click(screen.getByTestId('profile-template-work'));
  fireEvent.click(screen.getByTestId('profile-template-create'));
  const work = useProfiles.getState().profiles.find((p) => p.kind === 'work')!;
  fireEvent.click(screen.getByTestId(`profile-settings-${work.id}`));
  fireEvent.click(screen.getByTestId('profile-faces-open'));
  return work;
}

const stored = (id: string) => useProfiles.getState().profiles.find((p) => p.id === id)!;

describe('resolveFace / withFaceField', () => {
  const base = { name: 'Марта', status: 'В дорозі', bio: 'Гори' };

  it('рядок сфери замінює лише свої поля, решта — основне', () => {
    expect(resolveFace(base, { faces: { work: { name: 'Марта К.' } } }, 'work')).toEqual({
      name: 'Марта К.',
      status: 'В дорозі',
      bio: 'Гори',
    });
    expect(resolveFace(base, { faces: {} }, 'close')).toEqual(base);
  });

  it('порожнє значення знімає рядок, порожня сфера зникає', () => {
    const one = withFaceField({}, 'work', 'bio', ' Дизайн ');
    expect(one).toEqual({ work: { bio: 'Дизайн' } });
    expect(withFaceField(one, 'work', 'bio', '  ')).toEqual({});
  });
});

describe('Обличчя по сферах і «Бачать як» (profiles-w4)', () => {
  beforeEach(() => {
    localStorage.clear();
    useProfiles.setState(loadProfiles());
    useMessengerStore.setState({
      currentUser: { ...useMessengerStore.getState().currentUser, name: 'Марта', status: 'В дорозі', bio: '' },
    });
  });

  it('правка у вкладці Робота лягає в faces.work і не чіпає інших сфер', () => {
    const work = openFaces();
    fireEvent.click(screen.getByTestId('face-ring-work'));
    const name = screen.getByTestId('face-name') as HTMLInputElement;
    expect(name.placeholder).toBe('Марта');
    fireEvent.change(name, { target: { value: 'Марта Коваленко' } });
    fireEvent.blur(name);
    expect(stored(work.id).faces).toEqual({ work: { name: 'Марта Коваленко' } });

    fireEvent.click(screen.getByTestId('face-ring-friends'));
    expect((screen.getByTestId('face-name') as HTMLInputElement).value).toBe('');
  });

  it('очищене поле знімає рядок', () => {
    const work = openFaces();
    fireEvent.click(screen.getByTestId('face-ring-close'));
    const status = screen.getByTestId('face-status') as HTMLInputElement;
    fireEvent.change(status, { target: { value: 'Відпустка' } });
    fireEvent.keyDown(status, { key: 'Enter' });
    expect(stored(work.id).faces.close?.status).toBe('Відпустка');
    fireEvent.change(status, { target: { value: '' } });
    fireEvent.blur(status);
    expect(stored(work.id).faces).toEqual({});
  });

  it('«Бачать як» показує розвʼязане обличчя обраної сфери', () => {
    const work = openFaces();
    useProfiles.getState().update(work.id, { faces: { work: { status: 'На зв’язку' } } });
    fireEvent.click(screen.getByTestId('profile-see-as-open'));
    fireEvent.click(screen.getByTestId('face-ring-work'));
    expect(screen.getByTestId('see-as-name').textContent).toBe('Марта');
    expect(screen.getByTestId('see-as-status').textContent).toBe('На зв’язку');
    fireEvent.click(screen.getByTestId('face-ring-close'));
    expect(screen.getByTestId('see-as-status').textContent).toBe('В дорозі');
    expect(screen.queryByTestId('see-as-bio')).toBeNull();
  });

  it('«Змінити обличчя» з перегляду вертає до правки', () => {
    openFaces();
    fireEvent.click(screen.getByTestId('profile-see-as-open'));
    fireEvent.click(screen.getByTestId('see-as-change'));
    expect(screen.getByTestId('profile-faces')).toBeInTheDocument();
  });
});

describe("U12: «Обличчя» не губить свій рядок і з являється з анімацією", () => {
  beforeEach(() => {
    localStorage.clear();
    useProfiles.setState(loadProfiles());
  });

  it("після відкриття рядок лишається (розгорнутий), форма має вхідну анімацію, повторний дотик згортає", () => {
    openFaces();
    const row = screen.getByTestId("profile-faces-open");
    expect(row.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByTestId("profile-faces").className).toContain("f-fade-in");
    fireEvent.click(row);
    expect(screen.queryByTestId("profile-faces")).toBeNull();
    expect(screen.getByTestId("profile-faces-open").getAttribute("aria-expanded")).toBe("false");
  });
});

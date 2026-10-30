import type { ReactNode } from 'react';

export default function PhoneFrame({ children }: { children?: ReactNode }) {
  return (
    <div className="mx-auto aspect-[9/19.5] w-[320px] max-w-full rounded-phone border border-line bg-raised p-[10px]">
      <div className="h-full w-full overflow-y-auto rounded-[34px] bg-canvas p-4">
        {children}
      </div>
    </div>
  );
}

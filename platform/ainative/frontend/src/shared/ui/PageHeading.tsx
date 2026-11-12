import type { ReactNode } from 'react';
import './PageHeading.css';

export function PageHeading({ title, description, extra }: {
  title: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
}) {
  return (
    <div className="cf-page-heading">
      <div className="cf-page-heading-copy">
        <h1>{title}</h1>
        {description != null && <div className="cf-page-heading-description">{description}</div>}
      </div>
      {extra != null && <div className="cf-page-heading-extra">{extra}</div>}
    </div>
  );
}

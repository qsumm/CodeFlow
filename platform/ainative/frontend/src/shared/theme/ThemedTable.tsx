import { Table as AntTable, type TableProps } from 'antd';

/** Shared alignment contract: short values centered; long-text columns explicitly opt into left alignment.
 * 表头对齐必须跟随列声明(默认居中,显式 align 的列头尾一致),
 * 否则会出现「表头左对齐、单元格居中」的头尾错位。 */
function alignColumns<T extends object>(columns: TableProps<T>['columns']): TableProps<T>['columns'] {
  return columns?.map(column => ({
    ...column,
    align: column.align ?? 'center',
    ...('children' in column ? {children: alignColumns(column.children)} : {}),
    onHeaderCell: (...args) => {
      const props = column.onHeaderCell?.(...args);
      return {...props, style: {...props?.style, textAlign: column.align ?? 'center'}};
    },
  }));
}
export function Table<T extends object>(props: TableProps<T>) {
  return <AntTable<T> {...props} columns={alignColumns(props.columns)} />;
}
